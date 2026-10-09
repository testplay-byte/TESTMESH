package com.example.aimeshvision.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.example.aimeshvision.util.Perf
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardened TFLite wrapper.
 *
 * Fixes vs. the v1 model manager:
 *  - loads the BUNDLED asset model on first run (F1) and keeps labels wired
 *  - a [closed] flag + [AtomicBoolean] guard prevents use-after-close (F4)
 *  - runtime GPU failure automatically reloads on CPU once (F6)
 *  - thresholds are injectable and adjustable at runtime (F10)
 *  - all post-processing lives in [YoloPostProcessor] and degrades safely (F7)
 *  - INPUT LAYOUT IS DETECTED, not assumed: Ultralytics TFLite exports declare
 *    the input as NCHW [1,C,H,W] (the graph starts with an internal TRANSPOSE
 *    [0,2,3,1] -> NHWC), while other exports are NHWC [1,H,W,C]. Feeding
 *    interleaved RGB into a planar input silently produces garbage detections,
 *    so [loadModel] reads the real tensor shape and [letterbox] writes the
 *    buffer in the matching memory layout.
 *
 * Verified against the bundled model (training run 20261005):
 *  input  [1, 3, 512, 512]  NCHW, float32 0..1
 *  out[0] [1, 38, 5376]     transposed v8 layout: 4 box + 2 classes + 32 coeffs
 *  out[1] [1, 32, 128, 128] mask prototypes, NCHW
 */
class ModelManager {

    companion object {
        private const val TAG = "ModelManager"
        private const val FLOAT_BYTES = 4
        const val DEFAULT_CONFIDENCE = 0.35f
        const val DEFAULT_IOU = 0.45f
        const val DEFAULT_MAX_RESULTS = 20

        // ── split & detect: two-pass refine (user spec: full detection
        // FIRST, then targeted passes ONLY around the objects found) ─────────
        // Latency = 1 + up to REFINE_MAX inferences; empty frame areas cost
        // nothing extra (the old equal-grid split ran 12 tiles regardless).
        private const val REFINE_MAX = 4
        // Crop = detection box expanded by this factor per axis, so the
        // object isn't cut at the crop edge.
        private const val REFINE_EXPAND = 1.6f
        // Below this crop size (px) a refine adds no detail - the model
        // would only be re-upscaling a region it already saw clearly.
        private const val REFINE_MIN_CROP = 384
        // A refined detection replaces its pass-1 counterpart only when the
        // refine pass held at least this fraction of the confidence: refine
        // is opportunistic and must never downgrade a detection.
        private const val REFINE_KEEP_CONF = 0.8f
        // Same-class box IoU above which boxes across passes are the SAME
        // object (replace, not duplicate).
        private const val REFINE_MERGE_IOU = 0.3f
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: org.tensorflow.lite.gpu.GpuDelegate? = null
    private var currentModelFile: File? = null

    private val closed = AtomicBoolean(true)

    // ── resolved input geometry (set by [loadModel]) ─────────────────────────
    var inputWidth: Int = 512; private set
    var inputHeight: Int = 512; private set
    var inputChannels: Int = 3; private set

    /** True when the input tensor is [1,C,H,W] (planar) rather than [1,H,W,C]. */
    var isNchwLayout: Boolean = true; private set

    /** Letterbox parameters of the last inference (for overlay + mask mapping). */
    var lastPaddingX: Float = 0f; private set
    var lastPaddingY: Float = 0f; private set

    /** Class names indexed by class id - loaded from labels.txt (F1). */
    var classLabels: List<String> = emptyList()

    /** User-adjustable inference knobs (settings sheet). */
    @Volatile var confidenceThreshold: Float = DEFAULT_CONFIDENCE

    /**
     * Split & detect (two-pass refine): pass 1 runs the normal full-frame
     * inference; pass 2 re-infers small crops centred on the detections
     * found, where the object fills the model input (much higher effective
     * resolution), and swaps in the improved box/mask. Only areas WITH an
     * object get extra compute - cost 1 + up to [REFINE_MAX] inferences,
     * opt-in from settings.
     */
    @Volatile var tileInferenceEnabled: Boolean = false
    @Volatile var nmsIouThreshold: Float = DEFAULT_IOU

    // Mesh/outline geometry knobs (settings sheet "MESH & OUTLINE" section),
    // synced into the post-processor once per inference like conf/IoU.
    /** Blur passes for the mesh smoothing filter (0 = raw, 1 = default, 2 = max). */
    @Volatile var meshSmoothPasses: Int = 1

    /** Outline width multiplier on the adaptive band (1.0 = default). */
    @Volatile var ringWidthScale: Float = 1f

    private val postProcessor = YoloPostProcessor(
        confidenceThreshold, nmsIouThreshold, DEFAULT_MAX_RESULTS,
    )

    val isReady: Boolean get() = !closed.get() && interpreter != null

    /**
     * Returns a previous frame's mask/outline bitmaps to the decode pool
     * (called by the overlay when it drops the old results).
     */
    fun returnPrevious(results: List<Detection>) = postProcessor.returnPrevious(results)

    /** Size of the loaded model file in bytes (0 when nothing is loaded). */
    val modelFileSizeBytes: Long get() = currentModelFile?.length() ?: 0L

    /** Callback for runtime GPU → CPU fallback events (UI badge hook). */
    var onGpuFallback: ((reason: String) -> Unit)? = null

    // ── reusable scratch buffers (sized on model load; same every frame) ─────
    // The prototype output alone is ~8 MB (32x128x128 float32) - allocating
    // that per frame at 15-30 fps would thrash the GC, so every buffer is
    // allocated once per model load and reused.
    private var inputBuffer: ByteBuffer? = null
    private var boxBuffer: ByteBuffer? = null
    private var protoBuffer: ByteBuffer? = null
    private var pixelScratch: IntArray? = null

    // PERF: letterbox surfaces reused across frames. Creating a 512x512
    // bitmap + canvas every frame churned ~1 MB of bitmap memory per pass.
    private var letterboxBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null

    /**
     * Loads a .tflite model file. Succeeds even if the GPU delegate cannot init
     * (falls back to 4-thread XNNPACK CPU). Returns false only if the model
     * itself cannot be read.
     */
    fun loadModel(modelFile: File, useGpu: Boolean): Boolean {
        closed.set(true)
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
        releaseBuffers()

        return try {
            var delegateFailed = false
            val options = Interpreter.Options().apply {
                if (useGpu) {
                    try {
                        gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                        addDelegate(gpuDelegate)
                        Log.i(TAG, "GPU delegate enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU init failed, falling back to CPU", e)
                        delegateFailed = true
                        cpuOptions()
                    }
                } else {
                    cpuOptions()
                }
            }

            interpreter = Interpreter(modelFile, options)
            resolveInputLayout(interpreter!!.getInputTensor(0).shape())
            currentModelFile = modelFile
            closed.set(false)
            scratchBuffers()

            if (delegateFailed) onGpuFallback?.invoke("GPU unavailable - running on CPU")
            Log.i(
                TAG, "Model loaded ${modelFile.name} in=${inputWidth}x${inputHeight}x$inputChannels " +
                    "layout=${if (isNchwLayout) "NCHW" else "NHWC"}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
            interpreter = null
            gpuDelegate?.close(); gpuDelegate = null
            false
        }
    }

    /**
     * Reads the real input tensor shape and derives width/height/channels plus
     * the memory layout. Handles NCHW [1,C,H,W], NHWC [1,H,W,C], and
     * dynamic (-1) dims by falling back to sane defaults.
     */
    private fun resolveInputLayout(shape: IntArray) {
        when {
            // [1, C, H, W] - Ultralytics TFLite export shape (C is the small dim)
            shape.size == 4 && shape[1] in 1..4 -> {
                isNchwLayout = true
                inputChannels = shape[1]
                inputHeight = if (shape[2] > 0) shape[2] else 512
                inputWidth = if (shape[3] > 0) shape[3] else 512
            }
            // [1, H, W, C] - classic NHWC export (C is the small dim at the end)
            shape.size == 4 && shape[3] in 1..4 -> {
                isNchwLayout = false
                inputHeight = if (shape[1] > 0) shape[1] else 512
                inputWidth = if (shape[2] > 0) shape[2] else 512
                inputChannels = shape[3]
            }
            // Unknown/odd shape - assume the common NHWC default.
            else -> {
                isNchwLayout = false
                inputWidth = 512; inputHeight = 512; inputChannels = 3
            }
        }
    }

    /** (Re)allocates the per-frame scratch buffers for the current model. */
    private fun scratchBuffers() {
        val pixels = inputWidth * inputHeight
        inputBuffer = ByteBuffer.allocateDirect(pixels * inputChannels * FLOAT_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }
        pixelScratch = IntArray(pixels)

        // Pre-size the output buffers from the model's own tensor shapes so
        // runOnce never allocates in the hot path.
        val (boxIdx, maskIdx) = locateOutputs()
        if (boxIdx != -1) {
            boxBuffer = allocate(interpreter!!.getOutputTensor(boxIdx).shape())
        }
        if (maskIdx != -1) {
            protoBuffer = allocate(interpreter!!.getOutputTensor(maskIdx).shape())
        }
    }

    /**
     * Finds the detection tensor (rank 3, output 0 fallback) and the optional
     * mask-prototype tensor (rank 4) in the model's output list.
     */
    private fun locateOutputs(): Pair<Int, Int> {
        val interp = interpreter ?: return -1 to -1
        var boxIdx = -1
        var maskIdx = -1
        for (i in 0 until interp.outputTensorCount) {
            when (interp.getOutputTensor(i).shape().size) {
                3 -> if (boxIdx == -1) boxIdx = i
                4 -> maskIdx = i
            }
        }
        if (boxIdx == -1) boxIdx = 0
        return boxIdx to maskIdx
    }

    private fun Interpreter.Options.cpuOptions(): Interpreter.Options = apply {
        numThreads = 4
        useXNNPACK = true
    }

    /**
     * Runs detection on a bitmap. Never throws: any failure returns an empty
     * list, and a GPU failure at inference time triggers exactly one automatic
     * CPU reload + retry (F6).
     */
    fun runInference(bitmap: Bitmap): List<Detection> {
        if (closed.get()) return emptyList()

        // Split & detect: only worth the two-pass refine when the frame
        // exceeds the model input on at least one axis.
        if (tileInferenceEnabled &&
            (bitmap.width > inputWidth || bitmap.height > inputHeight)
        ) {
            var refined = runRefinePass(bitmap)
            if (refined.isNotEmpty() || !usedGpu) return refined
            // GPU died mid-refine -> reload on CPU and redo once (F6 parity
            // with the single-pass path).
            Log.w(TAG, "GPU refine inference failed - falling back to CPU")
            onGpuFallback?.invoke("GPU error - switched to CPU")
            val file = currentModelFile ?: return refined
            if (!loadModel(file, useGpu = false)) return refined
            return runRefinePass(bitmap)
        }

        val attempt = runOnce(bitmap)
        if (attempt != null || !usedGpu) return attempt ?: emptyList()

        // GPU failed mid-inference → reload on CPU and retry once.
        Log.w(TAG, "GPU inference failed - falling back to CPU")
        onGpuFallback?.invoke("GPU error - switched to CPU")
        val file = currentModelFile ?: return emptyList()
        if (!loadModel(file, useGpu = false)) return emptyList()
        return runOnce(bitmap) ?: emptyList()
    }

    @Volatile private var usedGpu = false

    /** Single inference pass; null means "failed" (caller may retry on CPU). */
    private fun runOnce(bitmap: Bitmap): List<Detection>? {
        val interp = interpreter ?: return null
        if (closed.get()) return null
        return try {
            usedGpu = gpuDelegate != null

            // Scale the source bitmap to fit the model square, centered.
            val scale = minOf(inputWidth / bitmap.width.toFloat(),
                              inputHeight / bitmap.height.toFloat())
            val newW = (bitmap.width * scale).toInt()
            val newH = (bitmap.height * scale).toInt()
            lastPaddingX = (inputWidth - newW) / 2f
            lastPaddingY = (inputHeight - newH) / 2f

            val buffer = inputBuffer ?: return null
            Perf.measure("infer-letterbox") { letterbox(bitmap, newW, newH, buffer) }

            val (boxIdx, maskIdx) = locateOutputs()
            val boxShape = interp.getOutputTensor(boxIdx).shape()
            val outBox = boxBuffer ?: return null
            val protoShape: IntArray?
            val outProto: ByteBuffer?
            if (maskIdx != -1) {
                protoShape = interp.getOutputTensor(maskIdx).shape()
                outProto = protoBuffer
            } else {
                protoShape = null; outProto = null
            }

            val inputs = arrayOf<Any>(buffer)
            val outputs = mutableMapOf<Int, Any>(
                boxIdx to outBox
            ).apply { outProto?.let { if (maskIdx != -1) put(maskIdx, it) } }

            Perf.measure("infer-interpreter") {
                interp.runForMultipleInputsOutputs(inputs, outputs)
            }
            outBox.rewind()
            outProto?.rewind()

            postProcessor.confidenceThreshold = confidenceThreshold
            postProcessor.nmsIouThreshold = nmsIouThreshold
            postProcessor.meshSmoothPasses = meshSmoothPasses
            postProcessor.ringWidthScale = ringWidthScale

            Perf.measure("infer-postprocess") {
                postProcessor.postProcess(
                    outBox, boxShape, outProto, protoShape,
                    inputWidth, inputHeight, lastPaddingX, lastPaddingY,
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    classLabels,
                    // A prototype tensor present means YOLO-seg: the 32
                    // trailing feature rows are mask coefficients, NOT class
                    // scores. Passing this explicitly beats guessing from the
                    // feature count alone.
                    expectMaskCoeffs = maskIdx != -1,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference run failed", e)
            null
        }
    }

    private fun allocate(shape: IntArray): ByteBuffer =
        ByteBuffer.allocateDirect(shape.fold(1) { acc, d -> acc * d } * FLOAT_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

    private fun releaseBuffers() {
        inputBuffer = null; boxBuffer = null; protoBuffer = null; pixelScratch = null
        letterboxBitmap?.recycle()
        letterboxBitmap = null; letterboxCanvas = null
    }

    /**
     * Letterboxes the bitmap into the model's input tensor with black padding,
     * writing floats in the layout the input tensor actually declares:
     *  - NCHW: channel planes in sequence (all R, then all G, then all B)
     *  - NHWC: interleaved per pixel (R,G,B, R,G,B, ...)
     */
    private fun letterbox(bitmap: Bitmap, newW: Int, newH: Int, buffer: ByteBuffer) {
        val pixels = pixelScratch ?: return

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        // Reuse the cached model-size surface (cleared to black each pass).
        val finalBitmap = letterboxBitmap ?: Bitmap.createBitmap(
            inputWidth, inputHeight, Bitmap.Config.ARGB_8888
        ).also { lb ->
            letterboxBitmap = lb
            letterboxCanvas = Canvas(lb)
        }
        val canvas = letterboxCanvas ?: Canvas(finalBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(scaled, lastPaddingX, lastPaddingY, null)

        finalBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        scaled.recycle()

        if (isNchwLayout) {
            // Planar: R plane first, then G, then B.
            val plane = inputWidth * inputHeight
            for (p in 0 until plane) {
                buffer.putFloat(((pixels[p] shr 16) and 0xFF) / 255.0f)
            }
            for (p in 0 until plane) {
                buffer.putFloat(((pixels[p] shr 8) and 0xFF) / 255.0f)
            }
            for (p in 0 until plane) {
                buffer.putFloat((pixels[p] and 0xFF) / 255.0f)
            }
        } else {
            // Interleaved: one RGB triple per pixel.
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                buffer.putFloat((pixel and 0xFF) / 255.0f)
            }
        }
        buffer.rewind()
    }

    // ── split & detect: two-pass refine ─────────────────────────────────────

    /**
     * Crop window for refining [box] (normalized full-frame): the box
     * expanded by [REFINE_EXPAND], centred on it, clamped to the frame,
     * floored at [REFINE_MIN_CROP]. Returns intArrayOf(x, y, w, h) in source
     * pixels, or null when the crop would just be the full frame again (no
     * refine value).
     */
    private fun cropRect(box: RectF, fw: Int, fh: Int): IntArray? {
        var cw = (box.right - box.left) * fw * REFINE_EXPAND
        var ch = (box.bottom - box.top) * fh * REFINE_EXPAND
        if (cw < REFINE_MIN_CROP) cw = minOf(REFINE_MIN_CROP.toFloat(), fw.toFloat())
        if (ch < REFINE_MIN_CROP) ch = minOf(REFINE_MIN_CROP.toFloat(), fh.toFloat())
        if (cw > fw) cw = fw.toFloat()
        if (ch > fh) ch = fh.toFloat()
        if (cw >= fw && ch >= fh) return null
        val w = cw.toInt().coerceAtMost(fw)
        val h = ch.toInt().coerceAtMost(fh)
        val x = ((box.left + box.right) / 2f * fw - w / 2f).toInt()
            .coerceIn(0, fw - w)
        val y = ((box.top + box.bottom) / 2f * fh - h / 2f).toInt()
            .coerceIn(0, fh - h)
        return intArrayOf(x, y, w, h)
    }

    /** Remaps a crop-normalized box to full-frame normalized coordinates. */
    private fun remapBox(b: RectF, cx: Int, cy: Int, cw: Int, ch: Int,
        fw: Int, fh: Int): RectF = RectF(
        (b.left * cw + cx) / fw,
        (b.top * ch + cy) / fh,
        (b.right * cw + cx) / fw,
        (b.bottom * ch + cy) / fh,
    )

    /**
     * Returns [det] (a crop-local detection) re-labelled so its mask covers
     * the crop rect of the source image: the mask bitmap itself is passed
     * through untouched (the overlay maps maskLeft/Top/Width/Height), so
     * there is NO resample and NO full-frame compositing canvas - a crop
     * mask stays ~128px (~64 KB) whatever the crop size.
     */
    private fun withCropMask(det: Detection, cx: Int, cy: Int, cw: Int, ch: Int,
        fw: Int, fh: Int): Detection = Detection(
        det.boundingBox, det.classId, det.confidence, det.label,
        det.maskCoefficients, det.maskBitmap,
        maskLeft = cx.toFloat() / fw,
        maskTop = cy.toFloat() / fh,
        maskWidth = cw.toFloat() / fw,
        maskHeight = ch.toFloat() / fh,
    )

    /** IoU of two normalized boxes (cross-tile merge). */
    private fun iouNorm(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Two-pass split & detect (user spec: full detection FIRST, then targeted
     * passes only around the objects found - never an equal grid).
     *
     * Pass 1 - the normal whole-frame inference: catches everything at
     * baseline resolution; empty areas cost nothing extra.
     * Pass 2 - for the top [REFINE_MAX] detections, crop an expanded window
     * around each and re-infer: the object now fills the 512px input (~3-10x
     * effective resolution for a hand-sized box) => sharper box + mask. The
     * refined result REPLACES its pass-1 counterpart when the refine pass
     * held >= [REFINE_KEEP_CONF] of the confidence (refine never downgrades);
     * objects visible only up close are ADDED when they don't duplicate any
     * accepted detection ([REFINE_MERGE_IOU]); pass-1 detections that fail
     * to refine keep their baseline version - the refine is opportunistic
     * by construction.
     *
     * Never throws: failed crops are skipped; an all-empty result with the
     * GPU active is treated as a GPU failure by the caller.
     */
    private fun runRefinePass(full: Bitmap): List<Detection> {
        if (interpreter == null || closed.get()) return emptyList()
        val fw = full.width
        val fh = full.height

        val tPass1 = Perf.start()
        val base = runOnce(full) ?: return emptyList()
        Perf.log("refine-pass1", tPass1)
        if (base.isEmpty()) return base

        val consumed = BooleanArray(base.size)
        val out = ArrayList<Detection>(base.size + REFINE_MAX)
        val order = base.indices.sortedByDescending { base[it].confidence }
            .take(REFINE_MAX)

        for (bi in order) {
            val b = base[bi]
            val cr = cropRect(b.boundingBox, fw, fh) ?: continue
            val cx = cr[0]
            val cy = cr[1]
            val cw = cr[2]
            val ch = cr[3]
            val tPass2 = Perf.start()
            val crop = Bitmap.createBitmap(full, cx, cy, cw, ch)
            val up = runOnce(crop) ?: continue
            Perf.log("refine-pass2-crop", tPass2)

            // Best same-class match to b = the same object, seen up close.
            var bestIdx = -1
            var bestIou = REFINE_MERGE_IOU
            for (i in up.indices) {
                if (up[i].classId != b.classId) continue
                val io = iouNorm(
                    remapBox(up[i].boundingBox, cx, cy, cw, ch, fw, fh),
                    b.boundingBox,
                )
                if (io >= bestIou) { bestIou = io; bestIdx = i }
            }

            if (bestIdx >= 0) {
                val r = up[bestIdx]
                if (r.confidence >= b.confidence * REFINE_KEEP_CONF) {
                    consumed[bi] = true
                    out.add(withCropMask(
                        Detection(
                            remapBox(r.boundingBox, cx, cy, cw, ch, fw, fh),
                            r.classId, r.confidence, r.label,
                            r.maskCoefficients, r.maskBitmap,
                        ),
                        cx, cy, cw, ch, fw, fh,
                    ))
                }
            }

            // Extra objects visible ONLY in this crop: added when they don't
            // duplicate anything already accepted.
            for (i in up.indices) {
                if (i == bestIdx) continue
                val rb = remapBox(up[i].boundingBox, cx, cy, cw, ch, fw, fh)
                if (rb.right <= rb.left || rb.bottom <= rb.top) continue
                var duplicate = false
                for (j in base.indices) {
                    if (j == bi) continue
                    if (base[j].classId == up[i].classId &&
                        iouNorm(rb, base[j].boundingBox) >= REFINE_MERGE_IOU
                    ) { duplicate = true; break }
                }
                if (!duplicate) {
                    for (e in out) {
                        if (e.classId == up[i].classId &&
                            iouNorm(rb, e.boundingBox) >= REFINE_MERGE_IOU
                        ) { duplicate = true; break }
                    }
                }
                if (!duplicate) {
                    out.add(withCropMask(
                        Detection(rb, up[i].classId, up[i].confidence,
                            up[i].label, up[i].maskCoefficients,
                            up[i].maskBitmap),
                        cx, cy, cw, ch, fw, fh,
                    ))
                }
            }
        }

        // Everything not replaced keeps its pass-1 (baseline) version.
        for (i in base.indices) if (!consumed[i]) out.add(base[i])

        Log.d(TAG, "split&detect refine: ${order.size}/${base.size} dets " +
            "refined -> ${out.size} total")
        return out
    }

    /**
     * Releases the interpreter. Safe to call anytime; blocks new inference
     * immediately via the closed flag (F4).
     */
    fun close() {
        closed.set(true)
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
        releaseBuffers()
    }
}
