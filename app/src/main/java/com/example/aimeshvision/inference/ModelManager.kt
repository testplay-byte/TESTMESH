package com.example.aimeshvision.inference

import android.content.Context
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

    /** Letterbox parameters of the last inference (for overlay + mask mapping). */
    var lastPaddingX: Float = 0f; private set
    var lastPaddingY: Float = 0f; private set

    var inputWidth: Int = 640; private set
    var inputHeight: Int = 640

    /** Class names indexed by class id - loaded from labels.txt (F1). */
    var classLabels: List<String> = emptyList()

    /** User-adjustable inference knobs (settings sheet). */
    @Volatile var confidenceThreshold: Float = DEFAULT_CONFIDENCE
    @Volatile var nmsIouThreshold: Float = DEFAULT_IOU

    private val postProcessor = YoloPostProcessor(
        confidenceThreshold, nmsIouThreshold, DEFAULT_MAX_RESULTS,
    )

    val isReady: Boolean get() = !closed.get() && interpreter != null

    /** Callback for runtime GPU → CPU fallback events (UI badge hook). */
    var onGpuFallback: ((reason: String) -> Unit)? = null

    /**
     * Loads a .tflite model file. Succeeds even if the GPU delegate cannot init
     * (falls back to 4-thread XNNPACK CPU). Returns false only if the model
     * itself cannot be read.
     */
    fun loadModel(modelFile: File, useGpu: Boolean): Boolean {
        closed.set(true)
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null

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

            val inputShape = interpreter!!.getInputTensor(0).shape()
            if (inputShape.size == 4) {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
            currentModelFile = modelFile
            closed.set(false)

            if (delegateFailed) onGpuFallback?.invoke("GPU unavailable - running on CPU")
            Log.i(TAG, "Model loaded (${inputWidth}x${inputHeight}): ${modelFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
            interpreter = null
            gpuDelegate?.close(); gpuDelegate = null
            false
        }
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

    private fun runOnce(bitmap: Bitmap): List<Detection>? {
        val interp = interpreter ?: return null
        if (closed.get()) return null
        return try {
            usedGpu = gpuDelegate != null

            val scale = minOf(inputWidth / bitmap.width.toFloat(),
                              inputHeight / bitmap.height.toFloat())
            val newW = (bitmap.width * scale).toInt()
            val newH = (bitmap.height * scale).toInt()
            lastPaddingX = (inputWidth - newW) / 2f
            lastPaddingY = (inputHeight - newH) / 2f

            val inputBuffer = letterbox(bitmap, newW, newH)

            // Locate the detection tensor (rank 3) and optional prototype tensor (rank 4).
            var boxIdx = -1
            var maskIdx = -1
            for (i in 0 until interp.outputTensorCount) {
                when (interp.getOutputTensor(i).shape().size) {
                    3 -> if (boxIdx == -1) boxIdx = i
                    4 -> maskIdx = i
                }
            }
            if (boxIdx == -1) boxIdx = 0

            val boxShape = interp.getOutputTensor(boxIdx).shape()
            val boxBuffer = allocate(boxShape)
            val protoShape: IntArray?
            val protoBuffer: ByteBuffer?
            if (maskIdx != -1) {
                protoShape = interp.getOutputTensor(maskIdx).shape()
                protoBuffer = allocate(protoShape)
            } else {
                protoShape = null; protoBuffer = null
            }

            val inputs = arrayOf<Any>(inputBuffer)
            val outputs = mutableMapOf<Int, Any>(
                boxIdx to boxBuffer
            ).apply { protoBuffer?.let { if (maskIdx != -1) put(maskIdx, it) } }

            interp.runForMultipleInputsOutputs(inputs, outputs)
            boxBuffer.rewind()
            protoBuffer?.rewind()

            postProcessor.confidenceThreshold = confidenceThreshold
            postProcessor.nmsIouThreshold = nmsIouThreshold

            postProcessor.postProcess(
                boxBuffer, boxShape, protoBuffer, protoShape,
                inputWidth, inputHeight, lastPaddingX, lastPaddingY,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                classLabels,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference run failed", e)
            null
        }
    }

    private fun allocate(shape: IntArray): ByteBuffer =
        ByteBuffer.allocateDirect(shape.fold(1) { acc, d -> acc * d } * FLOAT_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

    /** Letterboxes the bitmap into the model's input tensor with black padding. */
    private fun letterbox(bitmap: Bitmap, newW: Int, newH: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * FLOAT_BYTES)
            .apply { order(ByteOrder.nativeOrder()) }

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val finalBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(scaled, lastPaddingX, lastPaddingY, null)

        val pixels = IntArray(inputWidth * inputHeight)
        finalBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Releases the interpreter. Safe to call anytime; blocks new inference
     * immediately via the closed flag (F4).
     */
    fun close() {
        closed.set(true)
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
    }
}
