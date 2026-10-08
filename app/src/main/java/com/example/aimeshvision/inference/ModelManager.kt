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

        // Split & detect (tiled inference): overlap between adjacent tiles in
        // SOURCE pixels, and a hard cap on total tiles per frame so latency
        // stays bounded (tile axes shrink / tiles grow to fit).
        private const val TILE_OVERLAP = 128
        private const val MAX_TILES_TOTAL = 12
        // Cross-tile merge: two detections of the same class whose boxes
        // overlap at least this much are treated as the SAME object seen from
        // two tiles (typical split-across-seam IoU ~0.3-0.6; two distinct
        // adjacent objects rarely reach 0.15).
        private const val TILE_MERGE_IOU = 0.15f
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
     * Split & detect: when on, frames larger than the model input are cut
     * into overlapping tiles, each inferred at full model resolution, then
     * remapped and merged back to whole-frame detections. Catches small
     * objects the single-pass downscale would swallow, at tile-count x the
     * latency - opt-in from settings.
     */
    @Volatile var tileInferenceEnabled: Boolean = false
    @Volatile var nmsIouThreshold: Float = DEFAULT_IOU

    private val postProcessor = YoloPostProcessor(
        confidenceThreshold, nmsIouThreshold, DEFAULT_MAX_RESULTS,
    )

    val isReady: Boolean get() = !closed.get() && interpreter != null

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

        // Split & detect: only worth tiling when the frame exceeds the model
        // input on at least one axis.
        if (tileInferenceEnabled &&
            (bitmap.width > inputWidth || bitmap.height > inputHeight)
        ) {
            var tiled = runTiledPass(bitmap)
            if (tiled.isNotEmpty() || !usedGpu) return tiled
            // GPU died mid-tiling -> reload on CPU and redo once (F6 parity
            // with the single-pass path).
            Log.w(TAG, "GPU tiled inference failed - falling back to CPU")
            onGpuFallback?.invoke("GPU error - switched to CPU")
            val file = currentModelFile ?: return tiled
            if (!loadModel(file, useGpu = false)) return tiled
            return runTiledPass(bitmap)
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

    // ── split & detect: tiled inference ──────────────────────────────────────

    /**
     * Axis tiling: split [dim] into [cap] segments covered by tiles of at
     * least size [t] with >= [TILE_OVERLAP] overlap. Returns (starts, size).
     * When the natural count at size [t] exceeds [cap] (huge frames), tiles
     * GROW so [cap] tiles still cover the whole axis with overlap - the model
     * then sees each tile at a better scale than the single full-frame pass.
     */
    private fun axisTiles(dim: Int, t: Int, cap: Int): Pair<List<Int>, Int> {
        if (dim <= t) return listOf(0) to dim
        val ov = TILE_OVERLAP
        val stride = t - ov
        // Natural count at tile size t.
        var n = (dim - t + stride - 1) / stride + 1
        if (n > cap) n = cap
        if (n < 1) n = 1
        // Size that lets n tiles cover dim with >= ov overlap:
        //   n*size - (n-1)*ov >= dim  ->  size >= (dim + ov*(n-1)) / n
        val size = maxOf(t, (dim + ov * (n - 1) + n - 1) / n)
        val s2 = size - ov
        val starts = ArrayList<Int>(n)
        for (k in 0 until n) starts.add(minOf(k * s2, dim - size))
        return starts to size
    }

    /** Natural tile count for an axis (for the total-budget calculation). */
    private fun naturalCount(dim: Int, t: Int): Int {
        if (dim <= t) return 1
        val stride = t - TILE_OVERLAP
        return (dim - t + stride - 1) / stride + 1
    }

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
     * One tiled pass: grid over the frame (overlap >= [TILE_OVERLAP], total
     * tiles <= [MAX_TILES_TOTAL]), infer each tile at full model resolution,
     * remap boxes to whole-frame normalized coordinates, then greedily merge
     * cross-tile duplicates (same class, box IoU >= [TILE_MERGE_IOU]) into
     * one detection with the union box and the union mask (pasted at the
     * owner tile's offset into a whole-frame mask canvas).
     *
     * Never throws: failed tiles are skipped, an all-empty result with the
     * GPU active is treated as a GPU failure by the caller.
     */
    private fun runTiledPass(full: Bitmap): List<Detection> {
        val w = full.width
        val h = full.height
        if (interpreter == null || closed.get()) return emptyList()

        // Total-tile budget: shrink the longer axis first, then the other.
        var nx = naturalCount(w, inputWidth)
        var ny = naturalCount(h, inputHeight)
        while (nx * ny > MAX_TILES_TOTAL) {
            if (nx >= ny && nx > 1) nx-- else if (ny > 1) ny-- else break
        }
        val (xs, tw) = axisTiles(w, inputWidth, nx)
        val (ys, th) = axisTiles(h, inputHeight, ny)

        // Tile detections with boxes already remapped to full-frame coords;
        // parallel origin metadata for the mask paste (x, y, tw, th).
        val cand = ArrayList<Detection>(xs.size * ys.size * 2)
        val org = ArrayList<IntArray>(xs.size * ys.size * 2)
        for (y0 in ys) {
            for (x0 in xs) {
                val crop = Bitmap.createBitmap(full, x0, y0, tw, th)
                val dets = runOnce(crop) ?: continue
                for (d in dets) {
                    val box = RectF(
                        (d.boundingBox.left * tw + x0) / w,
                        (d.boundingBox.top * th + y0) / h,
                        (d.boundingBox.right * tw + x0) / w,
                        (d.boundingBox.bottom * th + y0) / h,
                    )
                    cand.add(
                        Detection(box, d.classId, d.confidence, d.label,
                            d.maskCoefficients, d.maskBitmap)
                    )
                    org.add(intArrayOf(x0, y0, tw, th))
                }
            }
        }
        if (cand.isEmpty()) return emptyList()

        // Greedy merge, highest confidence first: same class + enough box
        // overlap = the same object seen from two tiles.
        val order = (cand.indices).sortedByDescending { cand[it].confidence }
        val clusters = ArrayList<MutableList<Int>>()
        val clusterBox = ArrayList<RectF>()
        val clusterClass = ArrayList<Int>()
        for (idx in order) {
            var placed = false
            for (c in clusters.indices) {
                if (clusterClass[c] == cand[idx].classId &&
                    iouNorm(clusterBox[c], cand[idx].boundingBox) >= TILE_MERGE_IOU
                ) {
                    clusterBox[c].union(cand[idx].boundingBox)
                    clusters[c].add(idx)
                    placed = true
                    break
                }
            }
            if (!placed) {
                clusters.add(ArrayList<Int>(2).apply { add(idx) })
                clusterBox.add(RectF(cand[idx].boundingBox))
                clusterClass.add(cand[idx].classId)
            }
        }

        // Build the merged detections. Masks: paste every member's tile mask
        // into a whole-frame canvas (whole-frame grid derived from the shared
        // tile geometry, so every paste lands 1:1).
        val out = ArrayList<Detection>(clusters.size)
        for (c in clusters.indices) {
            val members = clusters[c]
            // First member is the highest-confidence (order is sorted) = rep.
            val rep = cand[members[0]]
            var maskOut: Bitmap? = null
            val anyMask = members.firstOrNull { cand[it].maskBitmap != null }
            if (anyMask != null) {
                val mw = cand[anyMask].maskBitmap!!.width
                val mh = cand[anyMask].maskBitmap!!.height
                if (mw > 0 && mh > 0) {
                    val fw = (mw * w + tw - 1) / tw
                    val fh = (mh * h + th - 1) / th
                    val buf = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                    val bc = Canvas(buf)
                    for (mi in members) {
                        val m = cand[mi].maskBitmap ?: continue
                        val o = org[mi]
                        val dx = (o[0] * mw) / o[2]
                        val dy = (o[1] * mh) / o[3]
                        // 1:1 paste (whole-frame grid scales identically) -
                        // union by SRC_OVER: opaque interiors stay opaque.
                        bc.drawBitmap(m, dx.toFloat(), dy.toFloat(), null)
                    }
                    maskOut = buf
                }
            }
            out.add(
                Detection(clusterBox[c], rep.classId, rep.confidence, rep.label,
                    rep.maskCoefficients, maskOut)
            )
        }
        Log.d(TAG, "split&detect: ${xs.size}x${ys.size} tiles -> " +
            "${cand.size} tile dets -> ${out.size} merged")
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
