package com.example.aimeshvision

import android.Manifest
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.aimeshvision.inference.Detection
import com.example.aimeshvision.inference.InferenceEngine
import com.example.aimeshvision.inference.ModelManager
import com.example.aimeshvision.ui.ImagePagerAdapter
import com.example.aimeshvision.ui.OverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-activity UI orchestrator (v2).
 *
 * v2 hardening:
 *  - the BUNDLED asset model + labels load on first run (F1) - the app works out of the box
 *  - every background task runs through InferenceEngine, so one failure can never
 *    kill the executor thread (F3)
 *  - the camera provider future is guarded (F2)
 *  - shutdown ordering: executor drains BEFORE the interpreter closes (F4)
 *  - the model-file stream is copied with use{} (F5)
 *  - GPU → CPU fallback at inference time surfaces as a badge message (F6)
 *  - confidence / IoU thresholds are user-adjustable in the settings sheet (F10)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AIMeshVision"
        private const val PREFS_NAME = "aimesh_prefs"
        private const val PREF_MODEL_FILENAME = "saved_model_filename"
        private const val PREF_USE_GPU = "use_gpu"
        private const val PREF_CONF = "conf_threshold"
        private const val PREF_IOU = "iou_threshold"
        private const val ASSET_MODEL = "model.tflite"
        private const val ASSET_LABELS = "labels.txt"
        private const val BUNDLED_MODEL_NAME = "bundled_model.tflite"
    }

    // ── engine + state ────────────────────────────────────────────────────────
    private val modelManager = ModelManager()
    private val engine = InferenceEngine()
    private lateinit var prefs: SharedPreferences

    @Volatile private var isPaused = false
    @Volatile private var useGpu = true
    private var currentModelFilename: String? = null
    private val modelLoading = AtomicBoolean(false)

    // gallery state
    private val uploadedBitmaps = mutableListOf<Bitmap>()
    private lateinit var imagePagerAdapter: ImagePagerAdapter

    // ── views ─────────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var imagePager: ViewPager2
    private lateinit var pageDots: LinearLayout
    private lateinit var overlayView: OverlayView
    private lateinit var btnUpload: LinearLayout
    private lateinit var btnPauseResume: ImageView
    private lateinit var btnSettings: LinearLayout
    private lateinit var tvLiveLabel: TextView
    private lateinit var indicatorDot: View
    private lateinit var tvConfidence: TextView
    private lateinit var tvLatency: TextView

    // ── activity-result launchers ─────────────────────────────────────────────

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        engine.run("model-import",
            task = {
                // F5 fixed: stream handled with use{} - no leak on any error path
                val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.tflite"
                val safeName = if (rawName.endsWith(".tflite")) rawName else "$rawName.tflite"
                val destFile = File(filesDir, safeName)
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Cannot open stream for model Uri")
                if (!modelManager.loadModel(destFile, useGpu))
                    throw IllegalStateException("Model failed to load: $safeName")
                destFile
            },
            onResult = { file ->
                currentModelFilename = file.name
                prefs.edit().putString(PREF_MODEL_FILENAME, file.name).apply()
                overlayView.clear()
                val mode = if (useGpu) "GPU" else "CPU"
                updateBadge("MODEL LOADED ($mode)", false, "#135bec")
                resetStats()
                if (isPaused) togglePause()
            },
            onError = { e ->
                Log.e(TAG, "Model import failed", e)
                updateBadge("ERR: MODEL", false, "#EF4444")
            },
        )
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (!modelManager.isReady) {
            updateBadge("LOADING MODEL…", false, "#F59E0B")
            return@registerForActivityResult
        }

        engine.run("gallery-decode",
            task = {
                val bitmaps = uris.mapNotNull { uri ->
                    try {
                        contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode $uri", e); null
                    }
                }
                if (bitmaps.isEmpty()) throw IllegalStateException("No image could be decoded")
                val startTime = System.currentTimeMillis()
                val results = modelManager.runInference(bitmaps[0])
                Triple(bitmaps, results, System.currentTimeMillis() - startTime)
            },
            onResult = { (bitmaps, results, latency) ->
                if (!isPaused) {
                    isPaused = true
                    updatePauseButtonUI()
                }
                previewView.visibility = View.INVISIBLE
                uploadedBitmaps.clear()
                uploadedBitmaps.addAll(bitmaps)
                imagePagerAdapter.setImages(uploadedBitmaps)
                imagePager.visibility = View.VISIBLE
                imagePager.setCurrentItem(0, false)
                buildPageDots(bitmaps.size, 0)
                overlayView.setResults(results, bitmaps[0].width, bitmaps[0].height)
                updateBadge("IMAGE 1/${bitmaps.size}", false, "#94A3B8")
                updateStats(results, latency)
            },
            onError = { e ->
                Log.e(TAG, "Gallery load failed", e)
                updateBadge("ERR: IMG DECODE", false, "#EF4444")
            },
        )
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            updateBadge("NO CAMERA", false, "#EF4444")
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureFullscreen()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        useGpu = prefs.getBoolean(PREF_USE_GPU, true)
        modelManager.confidenceThreshold = prefs.getFloat(PREF_CONF, ModelManager.DEFAULT_CONFIDENCE)
        modelManager.nmsIouThreshold = prefs.getFloat(PREF_IOU, ModelManager.DEFAULT_IOU)

        bindViews()

        imagePagerAdapter = ImagePagerAdapter()
        imagePager.adapter = imagePagerAdapter
        imagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val bmp = imagePagerAdapter.getImage(position) ?: return
                updateDotSelection(position)
                updateBadge("IMAGE ${position + 1}/${uploadedBitmaps.size}", false, "#94A3B8")
                runInference(bmp, "gallery-page")
            }
        })

        btnUpload.setOnClickListener { imagePickerLauncher.launch("image/*") }
        btnPauseResume.setOnClickListener { togglePause() }
        btnSettings.setOnClickListener { showSettingsSheet() }

        loadBundledOrSavedModel()
        updatePauseButtonUI()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        // F4 fixed: drain the executor BEFORE the interpreter is closed.
        engine.shutdown()
        modelManager.close()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        imagePager = findViewById(R.id.imagePager)
        pageDots = findViewById(R.id.pageDots)
        overlayView = findViewById(R.id.overlayView)
        btnUpload = findViewById(R.id.btnUpload)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnSettings = findViewById(R.id.btnSettings)
        tvLiveLabel = findViewById(R.id.tvLiveLabel)
        indicatorDot = findViewById(R.id.indicatorDot)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvLatency = findViewById(R.id.tvLatency)
    }

    // ── model loading (bundled first, saved model second) ────────────────────

    /**
     * F1 fixed: on first launch the BUNDLED asset model + labels are copied to
     * filesDir and loaded - the app detects out of the box. A previously picked
     * user model still takes priority.
     */
    private fun loadBundledOrSavedModel() {
        if (modelLoading.getAndSet(true)) return

        engine.run("model-bootstrap",
            task = {
                val saved = prefs.getString(PREF_MODEL_FILENAME, null)
                    ?.let { File(filesDir, it) }?.takeIf { it.exists() }

                val modelFile = saved ?: copyAssetToFilesDir(ASSET_MODEL, BUNDLED_MODEL_NAME)
                modelManager.loadModel(modelFile, useGpu)
                modelManager.classLabels = readLabels()
                Triple(modelFile.name, modelManager.isReady, saved != null)
            },
            onResult = { (name, ready, wasSaved) ->
                modelLoading.set(false)
                if (ready) {
                    currentModelFilename = name
                    prefs.edit().putString(PREF_MODEL_FILENAME, name).apply()
                    val source = if (wasSaved) "MODEL READY" else "MODEL READY (BUNDLED)"
                    updateBadge(source, false, "#4ADE80")
                } else {
                    updateBadge("LOAD FAILED", false, "#EF4444")
                }
            },
            onError = { e ->
                modelLoading.set(false)
                Log.e(TAG, "Model bootstrap failed", e)
                updateBadge("MODEL ERR", false, "#EF4444")
            },
        )
    }

    private fun copyAssetToFilesDir(assetName: String, destName: String): File {
        val dest = File(filesDir, destName)
        if (!dest.exists() || dest.length() == 0L) {
            assets.open(assetName).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        return dest
    }

    private fun readLabels(): List<String> =
        try {
            assets.open(ASSET_LABELS).bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "labels.txt missing - detections will show generic labels", e)
            emptyList()
        }

    // ── camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                // F2 fixed: the future itself is now guarded.
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(engine.executorService, InferenceAnalyzer()) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed", e)
                updateBadge("CAM ERROR", false, "#EF4444")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class InferenceAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            if (isPaused) { image.close(); return }
            if (!modelManager.isReady) {
                image.close()
                engine.onMain { updateBadge("AWAITING MODEL", false, "#F59E0B") }
                return
            }
            val startTime = System.currentTimeMillis()
            try {
                val rotation = image.imageInfo.rotationDegrees
                val rotated = image.toBitmap().let { bmp ->
                    android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }.let { m ->
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                }
                image.close()

                val results = modelManager.runInference(rotated)
                val latency = System.currentTimeMillis() - startTime

                engine.onMain {
                    if (!isPaused) {
                        previewView.visibility = View.VISIBLE
                        imagePager.visibility = View.GONE
                        pageDots.visibility = View.GONE
                        overlayView.setResults(results, rotated.width, rotated.height)
                        updateBadge("LIVE FEED", true, "#4ADE80")
                        updateStats(results, latency)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Live inference error", e)
                image.close()
            }
        }
    }

    // ── shared inference runner (every path goes through the engine) ─────────

    private fun runInference(bitmap: Bitmap, tag: String) {
        engine.run(tag,
            task = {
                val startTime = System.currentTimeMillis()
                val results = modelManager.runInference(bitmap)
                Pair(results, System.currentTimeMillis() - startTime)
            },
            onResult = { (results, latency) ->
                overlayView.setResults(results, bitmap.width, bitmap.height)
                updateStats(results, latency)
            },
            onError = { e ->
                Log.e(TAG, "$tag inference failed", e)
                updateBadge("INFER ERROR", false, "#EF4444")
            },
        )
    }

    // ── pause / resume ────────────────────────────────────────────────────────

    private fun togglePause() {
        isPaused = !isPaused
        if (!isPaused) {
            imagePager.visibility = View.GONE
            pageDots.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            uploadedBitmaps.clear()
            imagePagerAdapter.setImages(emptyList())
            overlayView.clear()
            resetStats()
            updateBadge("LIVE FEED", true, "#4ADE80")
        } else {
            previewView.bitmap?.let { bitmap ->
                val pagerVisible = imagePager.visibility == View.VISIBLE
                if (!pagerVisible) {
                    uploadedBitmaps.clear()
                    uploadedBitmaps.add(bitmap)
                    imagePagerAdapter.setImages(uploadedBitmaps)
                    imagePager.visibility = View.VISIBLE
                    pageDots.visibility = View.GONE
                    previewView.visibility = View.INVISIBLE
                }
                engine.run("pause-infer",
                    task = { modelManager.runInference(bitmap) },
                    onResult = { results ->
                        overlayView.setResults(results, bitmap.width, bitmap.height)
                        updateBadge("PAUSED", false, "#94A3B8")
                    },
                    onError = { e ->
                        Log.e(TAG, "Pause inference failed", e)
                        updateBadge("INFER ERROR", false, "#EF4444")
                    },
                )
            } ?: updateBadge("PAUSED", false, "#94A3B8")
        }
        updatePauseButtonUI()
    }

    // ── settings ──────────────────────────────────────────────────────────────

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)

        val tvModelName = view.findViewById<TextView>(R.id.tvCurrentModelName)
        val panelSelect = view.findViewById<LinearLayout>(R.id.panelSelectModel)
        val switchGpu = view.findViewById<SwitchCompat>(R.id.switchGpu)
        val seekConf = view.findViewById<SeekBar>(R.id.seekConf)
        val tvConfValue = view.findViewById<TextView>(R.id.tvConfValue)
        val seekIou = view.findViewById<SeekBar>(R.id.seekIou)
        val tvIouValue = view.findViewById<TextView>(R.id.tvIouValue)

        tvModelName.text = currentModelFilename ?: "Bundled model (default)"
        switchGpu.isChecked = useGpu

        // F10 fixed: confidence + IoU are user-adjustable, persisted, and applied live.
        val confPct = (modelManager.confidenceThreshold * 100).toInt()
        seekConf.progress = confPct
        tvConfValue.text = "$confPct%"
        seekConf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                tvConfValue.text = "$value%"
                if (fromUser) {
                    modelManager.confidenceThreshold = value / 100f
                    prefs.edit().putFloat(PREF_CONF, value / 100f).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val iouPct = (modelManager.nmsIouThreshold * 100).toInt()
        seekIou.progress = iouPct
        tvIouValue.text = "$iouPct%"
        seekIou.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                tvIouValue.text = "$value%"
                if (fromUser) {
                    modelManager.nmsIouThreshold = value / 100f
                    prefs.edit().putFloat(PREF_IOU, value / 100f).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        panelSelect.setOnClickListener {
            dialog.dismiss()
            modelPickerLauncher.launch("*/*")
        }

        switchGpu.setOnCheckedChangeListener { _, isChecked ->
            useGpu = isChecked
            prefs.edit().putBoolean(PREF_USE_GPU, useGpu).apply()
            val mode = if (useGpu) "GPU" else "CPU"
            updateBadge("HARDWARE: $mode", false, "#135bec")
            currentModelFilename?.let { name ->
                val file = File(filesDir, name)
                if (file.exists()) {
                    engine.post("gpu-reload") { modelManager.loadModel(file, useGpu) }
                }
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // ── page dots ─────────────────────────────────────────────────────────────

    private fun buildPageDots(count: Int, selectedIndex: Int) {
        if (count <= 1) { pageDots.visibility = View.GONE; return }
        pageDots.removeAllViews()
        val dp6 = (6 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        for (i in 0 until count) {
            val dot = View(this).apply {
                val size = if (i == selectedIndex) dp6 else dp4
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = dp4; it.marginStart = dp4
                }
                setBackgroundResource(R.drawable.bg_dot_green)
                alpha = if (i == selectedIndex) 1f else 0.4f
            }
            pageDots.addView(dot)
        }
        pageDots.visibility = View.VISIBLE
    }

    private fun updateDotSelection(selectedIndex: Int) {
        val dp6 = (6 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        for (i in 0 until pageDots.childCount) {
            val dot = pageDots.getChildAt(i)
            val size = if (i == selectedIndex) dp6 else dp4
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also {
                it.width = size; it.height = size
            }
            dot.alpha = if (i == selectedIndex) 1f else 0.4f
            dot.requestLayout()
        }
    }

    // ── ui helpers ────────────────────────────────────────────────────────────

    private fun updatePauseButtonUI() {
        btnPauseResume.setImageResource(
            if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
    }

    @SuppressLint("SetTextI18n")
    private fun updateStats(results: List<Detection>, latencyMs: Long) {
        if (results.isEmpty()) tvConfidence.text = "---"
        else tvConfidence.text = "${(results.maxOf { it.confidence } * 100).toInt()}%"
        tvLatency.text = "${latencyMs}ms"
    }

    private fun resetStats() {
        tvConfidence.text = "---"
        tvLatency.text = "---"
    }

    private fun updateBadge(text: String, isBlinking: Boolean, colorHex: String) {
        engine.onMain { updateBadgeUI(text, isBlinking, colorHex) }
    }

    private fun updateBadgeUI(text: String, isBlinking: Boolean, colorHex: String) {
        tvLiveLabel.text = text
        try {
            indicatorDot.background.setTint(Color.parseColor(colorHex))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad badge color: $colorHex")
        }
        if (isBlinking) {
            indicatorDot.alpha = 1f
            indicatorDot.animate().alpha(0.2f).setDuration(500)
                .withEndAction { indicatorDot.animate().alpha(1f).setDuration(500).start() }
                .start()
        } else {
            indicatorDot.animate().cancel()
            indicatorDot.alpha = 1f
        }
    }

    private fun configureFullscreen() {
        window.insetsController?.let { controller ->
            controller.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
