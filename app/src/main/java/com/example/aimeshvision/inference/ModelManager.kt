package com.example.aimeshvision.inference

import android.graphics.Bitmap
import android.graphics.Canvas
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
            letterbox(bitmap, newW, newH, buffer)

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

            interp.runForMultipleInputsOutputs(inputs, outputs)
            outBox.rewind()
            outProto?.rewind()

            postProcessor.confidenceThreshold = confidenceThreshold
            postProcessor.nmsIouThreshold = nmsIouThreshold

            postProcessor.postProcess(
                outBox, boxShape, outProto, protoShape,
                inputWidth, inputHeight, lastPaddingX, lastPaddingY,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                classLabels,
                // A prototype tensor present means YOLO-seg: the 32 trailing
                // feature rows are mask coefficients, NOT class scores. Passing
                // this explicitly beats guessing from the feature count alone.
                expectMaskCoeffs = maskIdx != -1,
            )
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
