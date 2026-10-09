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

        // Stage 1A: this many detections get full-frame mask decode; the rest
        // fall back to bbox-only (full frame costs ~524k MACs per detection).
        private const val MAX_FULL_DECODE = 6

        // All silhouette/outline geometry (spill cutoff, adaptive band
        // width, speckle floor) lives in MaskGeometry - a pure, unit-tested
        // object with no Android dependencies (see MaskGeometryTest).
        private const val MASK_COEFFS = 32   // YOLO-seg prototype coefficient count
        private const val MIN_BOXES = 3      // a sane box tensor has >= 3 feature rows
    }

    // ── settings knobs (live, read once per decode on the inference thread)
    /**
     * Mesh smoothness: number of blur+cutoff passes applied to the mask
     * before thresholding (0 = raw/staircase, 1 = default, 2 = extra
     * smooth). Backed by the settings sheet's "Mesh Smoothness" slider.
     */
    @Volatile var meshSmoothPasses: Int = 1

    /**
     * Outline width multiplier applied on top of the adaptive band width
     * (MaskGeometry reach), e.g. 0.5 = half, 1.0 = default, 1.5 = wide.
     * Backed by the settings sheet's "Outline Width" slider.
     */
    @Volatile var ringWidthScale: Float = 1f

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
        // per detection: stale 0xFFFFFFFF pixels from a previous detection
        // would bleed into this detection's bitmap (reviewer-found bug).
        val pixels = IntArray(usableProtoW * usableProtoH)
        // Scratch for the mesh smoothing filter (alpha-only blur passes).
        val blurTmp = IntArray(usableProtoW * usableProtoH)
        // Scratch for the outline band + the two chamfer distance fields it
        // is computed from. The band raster is RING_UPSCALE x finer than the
        // mesh (the outline must NOT share the mesh's low resolution - it is
        // drawn to the same full-frame rect, so 4x texels = a ~2.4-screen-px
        // line path instead of the mesh's ~9px staircase).
        val ringW = usableProtoW * MaskGeometry.RING_UPSCALE
        val ringH = usableProtoH * MaskGeometry.RING_UPSCALE
        val ring = IntArray(ringW * ringH)
        val distOut = IntArray(usableProtoW * usableProtoH)
        val distIn = IntArray(usableProtoW * usableProtoH)
        // Scratch for the speckle filter (flood fill: stack + seen + component).
        val floodStack = IntArray(usableProtoW * usableProtoH)
        val floodSeen = BooleanArray(usableProtoW * usableProtoH)
        val floodComp = IntArray(usableProtoW * usableProtoH)
        // Adaptive band sizing: per-texel component area (stamped by the
        // speckle flood) + nearest-owner area for exterior texels (stamped
        // by the chamfer sweeps). Zeroed per detection.
        val compSize = IntArray(usableProtoW * usableProtoH)
        val outSize = IntArray(usableProtoW * usableProtoH)

        // Stage 1A (accuracy): masks decode over the FULL usable proto area -
        // the old bbox-crop structurally dropped real mask protrusions beyond
        // the box (fingertips, points). Full decode costs ~524k MACs/det, so
        // detections beyond MAX_FULL_DECODE fall back to bbox-only decode.
        val tDecodeStart = android.os.SystemClock.elapsedRealtime()
        val fullDecodeCount = detections.size.coerceAtMost(MAX_FULL_DECODE)

        for ((detIdx, det) in detections.withIndex()) {
            try {
                val coeffs = det.maskCoefficients ?: continue
                if (coeffs.size != protoC) continue

                java.util.Arrays.fill(pixels, 0)
                java.util.Arrays.fill(ring, 0)
                java.util.Arrays.fill(compSize, 0)
                java.util.Arrays.fill(outSize, 0)
                val mask = acquireBitmap(usableProtoW, usableProtoH)
                det.outlineBitmap = null

                // Bbox bounds for the bbox-only fallback path.
                val boxL = (det.boundingBox.left * usableProtoW).toInt().coerceIn(0, usableProtoW - 1)
                val boxT = (det.boundingBox.top * usableProtoH).toInt().coerceIn(0, usableProtoH - 1)
                val boxR = (det.boundingBox.right * usableProtoW).toInt().coerceIn(0, usableProtoW - 1)
                val boxB = (det.boundingBox.bottom * usableProtoH).toInt().coerceIn(0, usableProtoH - 1)

                val yStart = if (detIdx < fullDecodeCount) 0 else boxT
                val yEnd = if (detIdx < fullDecodeCount) usableProtoH - 1 else boxB
                val xStart = if (detIdx < fullDecodeCount) 0 else boxL
                val xEnd = if (detIdx < fullDecodeCount) usableProtoW - 1 else boxR

                for (y in yStart..yEnd) {
                    for (x in xStart..xEnd) {
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

                        // MESH SMOOTHING FILTER: INSIDE the object only -
                        // alpha ≈ 128 + 64*sum (local linearization of
                        // sigmoid around the boundary), graded 129..255.
                        // Outside stays 0: writing alpha for any sum > -2
                        // (the a>0 version) tinted the WHOLE frame faintly -
                        // the fuzzy-mesh regression. The blur below then
                        // feathers the edge by a bounded ~1px.
                        if (sum > 0f) {
                            val a = (128 + sum * 64).toInt().coerceIn(129, 255)
                            pixels[y * usableProtoW + x] = (a shl 24) or 0x00FFFFFF
                        }
                    }
                }

                // 1) Smoothing filter (meshSmoothPasses separable 3x3
                //    passes): anti-aliased mesh edge; threshold at the
                //    cutoff = the mesh silhouette (MaskGeometry).
                // 2) Speckle erasure (mask only): tiny noise blobs must not
                //    tint anything (works with the outline OFF too); also
                //    stamps per-texel component areas for the adaptive band.
                // 3) OUTLINE BAND (full-frame decodes only): adaptive
                //    distance ramp around the final silhouette. Bbox-only
                //    decodes (tail of a crowded scene) skip it - their mask
                //    is cut at the box edge and a band there would outline
                //    that artificial cut.
                val fullDecode = detIdx < fullDecodeCount
                MaskGeometry.smoothMaskAlpha(pixels, blurTmp,
                    usableProtoW, usableProtoH, meshSmoothPasses)

                MaskGeometry.removeSpeckles(pixels, usableProtoW,
                    usableProtoH, floodStack, floodSeen, floodComp, compSize)

                if (fullDecode) {
                    MaskGeometry.buildOutlineBand(pixels, ring,
                        usableProtoW, usableProtoH, distOut, distIn,
                        compSize, outSize, ringWidthScale)
                }

                mask.setPixels(pixels, 0, usableProtoW, 0, 0, usableProtoW, usableProtoH)
                det.maskBitmap = mask
                if (fullDecode) {
                    val outline = acquireBitmap(ringW, ringH)
                    outline.setPixels(ring, 0, ringW, 0, 0, ringW, ringH)
                    det.outlineBitmap = outline
                }
            } catch (e: Exception) {
                // A single bad detection never takes down the batch (F7).
                det.maskBitmap = null
                det.outlineBitmap = null
            }
        }

        // Stage 1A perf gate: one log line per decode pass so the full-frame
        // cost is measured, not guessed. Remove once validated.
        val decodeMs = android.os.SystemClock.elapsedRealtime() - tDecodeStart
        if (decodeMs > 2) {
            android.util.Log.i("YoloPostProcessor",
                "decodeMasks: ${detections.size} det(s) in ${decodeMs}ms " +
                    "(full-frame for $fullDecodeCount)")
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

    // ── bitmap pool (swap-on-return) ───────────────────────────────────────
    // Mesh + outline bitmaps are handed to the overlay and returned at the
    // next setResults, so a per-size FREE LIST reuses them instead of
    // allocating a fresh 912x912 (3.3 MB) outline bitmap per detection per
    // frame. A single slot would have sufficed for one object, but a
    // crowded scene holds MAX_FULL_DECODE bitmaps alive at once - hence a
    // capped list per size (warm after the first few frames). Inference
    // thread acquires, main thread returns; [poolLock] is uncontended in
    // practice (one decode at a time). Over-cap returns simply go to GC.
    private val bitmapPool = HashMap<String, ArrayList<Bitmap>>()
    private val poolLock = Any()

    /** One full decode batch (masks + outlines) per size, + slack. */
    private val POOL_CAP_PER_SIZE = 12

    /** Takes a cleared-or-fresh bitmap of the given size from the pool. */
    private fun acquireBitmap(w: Int, h: Int): Bitmap {
        val key = "$w x $h"
        synchronized(poolLock) {
            val list = bitmapPool[key]
            if (list != null && list.isNotEmpty()) {
                val b = list.removeAt(list.size - 1)
                if (b.width == w && b.height == h) return b
                // Wrong size (orientation/model change): drop it to GC.
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** Gives a bitmap back for the next frame to reuse (never recycled -
     *  an over-cap return just goes to GC normally). */
    private fun returnBitmap(b: Bitmap) {
        val key = "${b.width} x ${b.height}"
        synchronized(poolLock) {
            val list = bitmapPool.getOrPut(key) { ArrayList() }
            if (list.size < POOL_CAP_PER_SIZE) list.add(b)
        }
    }

    /**
     * Returns the previous frame's mask/outline bitmaps to the pool.
     * Called by the overlay at setResults time, AFTER it has dropped the
     * old list and while it is still on the main thread (no draw can be
     * using them concurrently - onDraw is main-thread too).
     */
    fun returnPrevious(results: List<Detection>) {
        for (d in results) {
            d.maskBitmap?.let { returnBitmap(it) }
            d.outlineBitmap?.let { returnBitmap(it) }
        }
    }
}
