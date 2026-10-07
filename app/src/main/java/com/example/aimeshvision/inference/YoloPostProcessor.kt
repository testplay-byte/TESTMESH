package com.example.aimeshvision.inference

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Pure post-processing for YOLO detection/segmentation outputs.
 *
 * Handles both layouts found in exported YOLO TFLite models:
 *  - YOLOv5: [1, boxes, features] WITH objectness at index 4
 *  - YOLOv8: [1, features, boxes] (transposed), no objectness
 * and YOLO-seg models, which append 32 prototype mask coefficients
 * to each detection row.
 *
 * All thresholds are constructor-injected (user-adjustable via settings).
 * This class holds no Android model state - it is pure logic on buffers.
 */
class YoloPostProcessor(
    @Volatile var confidenceThreshold: Float = 0.35f,
    @Volatile var nmsIouThreshold: Float = 0.45f,
    @Volatile var maxResults: Int = 20,
) {

    companion object {
        private const val FLOAT_BYTES = 4
        private const val MASK_COEFFS = 32   // YOLO-seg prototype coefficient count
        private const val MIN_BOXES = 3      // a sane box tensor has >= 3 feature rows
    }

    /**
     * Full post-process chain: parse raw output → NMS → decode segmentation masks.
     *
     * @param boxBuffer   raw floats of the detection output tensor
     * @param boxShape    its shape ([1, A, B])
     * @param protoBuffer raw floats of the mask prototype tensor (null for detect models)
     * @param protoShape  its shape ([1, C, H, W] or [1, H, W, C])
     * @param inputW/H    model input resolution (letterboxed space)
     * @param padX/padY   letterbox padding in model pixels
     * @param origW/H     source image resolution (results are normalized to this)
     * @param labels      class names indexed by class id
     */
    fun postProcess(
        boxBuffer: java.nio.ByteBuffer,
        boxShape: IntArray,
        protoBuffer: java.nio.ByteBuffer?,
        protoShape: IntArray?,
        inputW: Int,
        inputH: Int,
        padX: Float,
        padY: Float,
        origW: Float,
        origH: Float,
        labels: List<String>?,
        expectMaskCoeffs: Boolean = false,
    ): List<Detection> {
        val raw = parseOutput(
            boxBuffer, boxShape, labels, inputW, inputH, padX, padY, origW, origH,
            expectMaskCoeffs,
        )
        val accepted = applyNms(raw)

        if (protoBuffer != null && protoShape != null && accepted.isNotEmpty()) {
            decodeMasks(accepted, protoBuffer, protoShape, inputW, inputH, padX, padY)
        }
        return accepted
    }

    /**
     * Parses the raw detection tensor into normalized [Detection]s.
     * Throws IllegalArgumentException only for hopeless shapes - callers guard.
     *
     * @param expectMaskCoeffs when true (prototype tensor present), the trailing
     *   MASK_COEFFS feature rows are mask coefficients; when false they are
     *   class scores. The old heuristic (feature count > 32+4) misclassified
     *   detect-only models with many classes.
     */
    private fun parseOutput(
        output: java.nio.ByteBuffer,
        shape: IntArray,
        labels: List<String>?,
        inputW: Int,
        inputH: Int,
        padX: Float,
        padY: Float,
        origW: Float,
        origH: Float,
        expectMaskCoeffs: Boolean,
    ): List<Detection> {
        if (shape.size < 3) return emptyList()

        val dim1 = shape[1]
        val dim2 = shape[2]

        // YOLOv8 exports transposed [1, features, boxes]; v5 keeps [1, boxes, features].
        val isTransposed = dim1 < dim2
        val numDetections = if (isTransposed) dim2 else dim1
        val numFeatures = if (isTransposed) dim1 else dim2
        if (numFeatures < MIN_BOXES || numDetections <= 0) return emptyList()

        val hasObjectness = !isTransposed
        var numClasses = numFeatures - 4 - (if (hasObjectness) 1 else 0)

        // YOLO-seg appends MASK_COEFFS prototype coefficients after the class scores.
        // Presence is decided by the model graph (prototype output tensor), with the
        // legacy feature-count heuristic as a fallback for shape-only callers.
        val hasMaskCoeffs = expectMaskCoeffs || numClasses > MASK_COEFFS
        if (hasMaskCoeffs) numClasses -= MASK_COEFFS
        if (numClasses <= 0) return emptyList()

        // Read the whole tensor once into a flat array for random access.
        val total = numDetections * numFeatures
        val flat = FloatArray(total)
        output.asFloatBuffer().get(flat)

        fun feat(detection: Int, feature: Int): Float =
            if (isTransposed) flat[feature * numDetections + detection]
            else flat[detection * numFeatures + feature]

        val usableW = inputW - 2 * padX
        val usableH = inputH - 2 * padY
        if (usableW <= 0 || usableH <= 0) return emptyList()

        val results = mutableListOf<Detection>()

        for (d in 0 until numDetections) {
            val objConf = if (hasObjectness) feat(d, 4) else 1.0f
            val classOffset = if (hasObjectness) 5 else 4

            var bestProb = 0f
            var bestClass = 0
            for (c in 0 until numClasses) {
                val prob = feat(d, classOffset + c)
                if (prob > bestProb) { bestProb = prob; bestClass = c }
            }

            val confidence = objConf * bestProb
            if (confidence < confidenceThreshold) continue

            // Model coordinates may arrive in pixels (v5) or normalized (v8).
            var cx = feat(d, 0)
            var cy = feat(d, 1)
            var bw = feat(d, 2)
            var bh = feat(d, 3)
            if (cx > 1.5f || bw > 1.5f) {
                cx /= inputW; bw /= inputW
                cy /= inputH; bh /= inputH
            }

            // Strip the letterbox padding: map model space back to source space.
            val cxOrig = (cx * inputW - padX) / usableW
            val cyOrig = (cy * inputH - padY) / usableH
            val bwOrig = bw * inputW / usableW
            val bhOrig = bh * inputH / usableH

            val box = RectF(
                cxOrig - bwOrig / 2f, cyOrig - bhOrig / 2f,
                cxOrig + bwOrig / 2f, cyOrig + bhOrig / 2f,
            )

            val coeffs = if (hasMaskCoeffs)
                FloatArray(MASK_COEFFS) { feat(d, classOffset + numClasses + it) } else null

            results.add(
                Detection(
                    boundingBox = box,
                    classId = bestClass,
                    confidence = confidence,
                    label = labels?.getOrNull(bestClass) ?: "Obj $bestClass",
                    maskCoefficients = coeffs,
                )
            )
        }
        return results.sortedByDescending { it.confidence }
    }

    /** Greedy per-class NMS, capped at [maxResults]. */
    private fun applyNms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val accepted = mutableListOf<Detection>()

        while (sorted.isNotEmpty() && accepted.size < maxResults) {
            val best = sorted.removeAt(0)
            accepted.add(best)
            sorted.removeAll { candidate ->
                candidate.classId == best.classId &&
                    computeIoU(best.boundingBox, candidate.boundingBox) > nmsIouThreshold
            }
        }
        return accepted
    }

    /**
     * Decodes YOLO-seg prototype masks into per-detection bitmaps.
     * Malformed tensors degrade to "no mask" for that detection - never throw (F7).
     */
    private fun decodeMasks(
        detections: List<Detection>,
        protoBuffer: java.nio.ByteBuffer,
        protoShape: IntArray,
        inputW: Int,
        inputH: Int,
        padX: Float,
        padY: Float,
    ) {
        if (protoShape.size != 4) return

        val isNhwc = protoShape[3] == MASK_COEFFS
        val protoC = if (isNhwc) protoShape[3] else protoShape[1]
        val protoH = if (isNhwc) protoShape[1] else protoShape[2]
        val protoW = if (isNhwc) protoShape[2] else protoShape[3]
        if (protoH <= 0 || protoW <= 0 || protoC <= 0) return

        val usableW = inputW - 2 * padX
        val usableH = inputH - 2 * padY
        if (usableW <= 0 || usableH <= 0) return

        // Area of the prototype that contains real image data (padding excluded).
        val usableProtoW = (protoW * (usableW / inputW.toFloat())).toInt().coerceIn(1, protoW)
        val usableProtoH = (protoH * (usableH / inputH.toFloat())).toInt().coerceIn(1, protoH)
        val maskPadX = (padX / inputW * protoW).toInt()
        val maskPadY = (padY / inputH * protoH).toInt()

        val protoData = FloatArray(protoBuffer.capacity() / FLOAT_BYTES)
        protoBuffer.rewind()
        protoBuffer.asFloatBuffer().get(protoData)

        // PERF: one scratch array reused across detections. It MUST be zeroed
        // per detection: the write loop covers only the bbox area, and stale
        // 0xFFFFFFFF pixels from a previous detection would bleed into this
        // detection's bitmap (reviewer-found contamination bug).
        val pixels = IntArray(usableProtoW * usableProtoH)

        for (det in detections) {
            try {
                val coeffs = det.maskCoefficients ?: continue
                if (coeffs.size != protoC) continue

                java.util.Arrays.fill(pixels, 0)
                val mask = Bitmap.createBitmap(usableProtoW, usableProtoH, Bitmap.Config.ARGB_8888)

                val boxL = (det.boundingBox.left * usableProtoW).toInt().coerceIn(0, usableProtoW - 1)
                val boxT = (det.boundingBox.top * usableProtoH).toInt().coerceIn(0, usableProtoH - 1)
                val boxR = (det.boundingBox.right * usableProtoW).toInt().coerceIn(0, usableProtoW - 1)
                val boxB = (det.boundingBox.bottom * usableProtoH).toInt().coerceIn(0, usableProtoH - 1)

                for (y in boxT..boxB) {
                    for (x in boxL..boxR) {
                        val px = x + maskPadX
                        val py = y + maskPadY
                        if (px < 0 || px >= protoW || py < 0 || py >= protoH) continue

                        var sum = 0f
                        if (isNhwc) {
                            var idx = py * protoW * protoC + px * protoC
                            for (c in 0 until protoC) sum += coeffs[c] * protoData[idx++]
                        } else {
                            for (c in 0 until protoC) {
                                sum += coeffs[c] * protoData[c * protoH * protoW + py * protoW + px]
                            }
                        }

                        // PERF: sigmoid(sum) > 0.5 is mathematically identical
                        // to sum > 0 - the exp() call (per pixel per detection,
                        // the hottest line in the pipeline) is skipped entirely.
                        if (sum > 0f) pixels[y * usableProtoW + x] = 0xFFFFFFFF.toInt()
                    }
                }
                mask.setPixels(pixels, 0, usableProtoW, 0, 0, usableProtoW, usableProtoH)
                det.maskBitmap = mask
            } catch (e: Exception) {
                // A single bad detection never takes down the batch (F7).
                det.maskBitmap = null
            }
        }
    }

    /** Intersection-over-union of two normalized boxes. */
    private fun computeIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersect = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - intersect
        return if (union <= 0f) 0f else intersect / union
    }
}
