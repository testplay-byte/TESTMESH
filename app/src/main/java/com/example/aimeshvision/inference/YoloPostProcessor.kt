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

        // MESH SPILL CUTOFF: after the smoothing blur, alpha below this is
        // zeroed. One separable 3x3 pass spreads OUTSIDE the object by at
        // most (255+0+0)/3 = 85, while genuine interior edge pixels start at
        // 113 - so 96 provably removes the entire outside feather (the mesh
        // visibly spilling past the hand: each mask px is ~8 screen px) while
        // keeping every interior gradient pixel. Smoothing stays, spill goes.
        // 104 (was 96): a device test still saw slight spill at soft,
        // low-confidence edges where interior blur values dip into the 96-104
        // band. 104 stays provably below the interior edge start (113) and
        // far above outside-feather max (85).
        private const val MESH_ALPHA_CUTOFF = 104

        // ── IMAGE-SPACE OUTLINE BAND (outline redesign) ─────────────────────
        // The outline is not vector geometry: it is a per-texel alpha ramp
        // over the DISTANCE TO THE MESH BOUNDARY (3-4 chamfer transform of
        // the thresholded mask), straddling the silhouette edge:
        //
        //   - outer ramp: alpha 255 at the boundary texel, fading to 0 over
        //     RING_OUT_TEXELS (~16 screen px per texel at phone scale);
        //   - inner ramp: alpha 255 ON the boundary texel(s), fading to 0
        //     over RING_IN_TEXELS - the line sits ON the mesh edge/corners
        //     (half in, half out) instead of floating outside it.
        //
        // Distance-based (NOT harvested from the blur's exterior feather as
        // the first attempt was): the separable box blur only spreads along
        // axes, so its feather DIES at corners and diagonal edges - that is
        // exactly the "outline looks dotted / misses the corners" device
        // report. A chamfer distance ramp covers every texel within range by
        // construction: solid around corners, no threshold gaps.
        // One boundary definition still serves both layers (band peaks AT the
        // mesh's own level set), so double lines remain unrepresentable.
        private const val RING_OUT_TEXELS = 2f
        private const val RING_IN_TEXELS = 1f

        // SPECKLE FILTER (moved here from OverlayView so it applies whether
        // or not the outline is drawn - previously speckles tinted the frame
        // with the outline off): proto noise produces small satellite blobs
        // next to the real object (measured: 436/403/149-px speckles beside
        // a 5810-px cat) that float as tint patches AND would grow their own
        // outline band. A component below this fraction of the LARGEST
        // component (absolute floor MIN_SPECKLE_PX) is erased from the mask
        // and its band, while genuine same-size objects (two hands) stay far
        // above the floor.
        private const val SPECKLE_FRACTION = 0.10f
        private const val MIN_SPECKLE_PX = 12
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
        // per detection: stale 0xFFFFFFFF pixels from a previous detection
        // would bleed into this detection's bitmap (reviewer-found bug).
        val pixels = IntArray(usableProtoW * usableProtoH)
        // Scratch for the mesh smoothing filter (alpha-only blur passes).
        val blurTmp = IntArray(usableProtoW * usableProtoH)
        // Scratch for the outline band (zeroed per detection) + the two
        // chamfer distance fields it is computed from.
        val ring = IntArray(usableProtoW * usableProtoH)
        val distOut = IntArray(usableProtoW * usableProtoH)
        val distIn = IntArray(usableProtoW * usableProtoH)
        // Scratch for the speckle filter (flood fill: stack + seen + component).
        val floodStack = IntArray(usableProtoW * usableProtoH)
        val floodSeen = BooleanArray(usableProtoW * usableProtoH)
        val floodComp = IntArray(usableProtoW * usableProtoH)

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
                val mask = Bitmap.createBitmap(usableProtoW, usableProtoH, Bitmap.Config.ARGB_8888)
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

                // 1) Smoothing filter (ONE separable 3x3 pass): anti-aliased
                //    mesh edge; threshold at the cutoff = the mesh silhouette.
                // 2) Speckle erasure (mask only): tiny noise blobs must not
                //    tint anything (works with the outline OFF too), and the
                //    band below derives from the cleaned mesh, so erased
                //    speckles never grow an outline.
                // 3) OUTLINE BAND (full-frame decodes only): distance ramp
                //    around the final silhouette. Bbox-only decodes (tail of
                //    a crowded scene) skip it - their mask is cut at the box
                //    edge and a band there would outline that artificial cut.
                val fullDecode = detIdx < fullDecodeCount
                smoothMaskAlpha(pixels, blurTmp, usableProtoW, usableProtoH)

                removeSpeckles(pixels, usableProtoW, usableProtoH,
                    floodStack, floodSeen, floodComp)

                if (fullDecode) {
                    buildOutlineBand(pixels, ring, usableProtoW,
                        usableProtoH, distOut, distIn)
                }

                mask.setPixels(pixels, 0, usableProtoW, 0, 0, usableProtoW, usableProtoH)
                det.maskBitmap = mask
                if (fullDecode) {
                    val outline = Bitmap.createBitmap(usableProtoW, usableProtoH,
                        Bitmap.Config.ARGB_8888)
                    outline.setPixels(ring, 0, usableProtoW, 0, 0,
                        usableProtoW, usableProtoH)
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

    /**
     * Mesh smoothing filter: ONE separable 3x3 box-blur pass over the alpha
     * bytes of [pix] (white RGB preserved). Smooths the proto-grid staircase
     * at the source, so the tinted mesh edge arrives anti-aliased. Exactly
     * one pass - a second would erode sub-3px features and merge 1px gaps
     * (measured on a byte-exact port). The V-pass applies the spill cutoff:
     * final alpha >= [MESH_ALPHA_CUTOFF] keeps its value, everything else is
     * zeroed - the mesh silhouette is exactly the cutoff level set, and the
     * outline band is derived from that silhouette afterwards (see
     * [buildOutlineBand]; both layers share ONE boundary definition).
     *
     * Uses [tmp] as row-pass scratch (same length as [pix], fully rewritten
     * each pass, so stale contents are never read).
     */
    private fun smoothMaskAlpha(pix: IntArray, tmp: IntArray,
        w: Int, h: Int) {
        // Horizontal pass: pix -> tmp (edge pixels duplicate the neighbor).
        for (y in 0 until h) {
            val r = y * w
            var a0 = pix[r] ushr 24
            for (x in 0 until w) {
                val a1 = pix[r + x] ushr 24
                val a2 = if (x + 1 < w) pix[r + x + 1] ushr 24 else a1
                val av = (a0 + a1 + a2) / 3
                tmp[r + x] = if (av > 0) (av shl 24) or 0x00FFFFFF else 0
                a0 = a1
            }
        }
        // Vertical pass: tmp -> pix, with the spill cutoff (snaps the tint
        // edge back to the object boundary - see doc above).
        for (x in 0 until w) {
            var a0 = tmp[x] ushr 24
            for (y in 0 until h) {
                val i = y * w + x
                val a1 = tmp[i] ushr 24
                val a2 = if (y + 1 < h) tmp[(y + 1) * w + x] ushr 24 else a1
                val av = (a0 + a1 + a2) / 3
                pix[i] = if (av >= MESH_ALPHA_CUTOFF) (av shl 24) or 0x00FFFFFF else 0
                a0 = a1
            }
        }
    }

    /**
     * Builds the outline band around the mesh silhouette in [pix] (alpha >=
     * [MESH_ALPHA_CUTOFF] = solid): a 3-4 chamfer distance transform from
     * the boundary on BOTH sides, converted to an alpha ramp that peaks AT
     * the boundary and fades over [RING_OUT_TEXELS] outward /
     * [RING_IN_TEXELS] inward.
     *
     * Distance-based rather than feather-based because a separable box blur
     * only spreads along the axes: its exterior feather dies at corners and
     * diagonal edges, which rendered the first band attempt as a DOTTED line
     * that missed corners (device report). Every texel within range gets a
     * value here by construction - the line is solid around corners and
     * straddles the edge (half in the mesh, half out) so it visually sits
     * ON the silhouette.
     *
     * Cost: 4 sweep passes + 1 write pass over w*h (16k texels) on the
     * inference thread, zero allocation (caller scratch [dOut]/[dIn]).
     */
    private fun buildOutlineBand(pix: IntArray, ring: IntArray, w: Int, h: Int,
        dOut: IntArray, dIn: IntArray,
    ) {
        val n = w * h
        val BIG = 0x3fffffff
        // Seeds: distance-to-mesh starts 0 ON the mesh; distance-to-exterior
        // starts 0 OFF the mesh; everything else is "unreached" (BIG).
        java.util.Arrays.fill(dOut, BIG)
        java.util.Arrays.fill(dIn, BIG)
        for (i in 0 until n) {
            if ((pix[i] ushr 24) >= MESH_ALPHA_CUTOFF) dOut[i] = 0 else dIn[i] = 0
        }
        // 3-4 chamfer: cardinals cost 3, diagonals 4 (texel unit = 3).
        // Forward sweep: propagate from top/left neighbours...
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var o = dOut[i]
                var d = dIn[i]
                if (x > 0) {
                    val t = dOut[i - 1] + 3; if (t < o) o = t
                    val u = dIn[i - 1] + 3; if (u < d) d = u
                }
                if (y > 0) {
                    var t = dOut[i - w] + 3; if (t < o) o = t
                    var u = dIn[i - w] + 3; if (u < d) d = u
                    if (x > 0) {
                        t = dOut[i - w - 1] + 4; if (t < o) o = t
                        u = dIn[i - w - 1] + 4; if (u < d) d = u
                    }
                    if (x < w - 1) {
                        t = dOut[i - w + 1] + 4; if (t < o) o = t
                        u = dIn[i - w + 1] + 4; if (u < d) d = u
                    }
                }
                dOut[i] = o
                dIn[i] = d
            }
        }
        // ... then a backward sweep from bottom/right neighbours completes it.
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var o = dOut[i]
                var d = dIn[i]
                if (x < w - 1) {
                    val t = dOut[i + 1] + 3; if (t < o) o = t
                    val u = dIn[i + 1] + 3; if (u < d) d = u
                }
                if (y < h - 1) {
                    var t = dOut[i + w] + 3; if (t < o) o = t
                    var u = dIn[i + w] + 3; if (u < d) d = u
                    if (x < w - 1) {
                        t = dOut[i + w + 1] + 4; if (t < o) o = t
                        u = dIn[i + w + 1] + 4; if (u < d) d = u
                    }
                    if (x > 0) {
                        t = dOut[i + w - 1] + 4; if (t < o) o = t
                        u = dIn[i + w - 1] + 4; if (u < d) d = u
                    }
                }
                dOut[i] = o
                dIn[i] = d
            }
        }
        // Convert distances to an alpha ramp peaking at the boundary:
        //   alpha(t) = 255 * clamp01((R + 1 - t) / R),  t in texels.
        // Outer side: t=1 (boundary-adjacent) -> 255, t=R+1 -> 0.
        // Inner side: same shape inside the mesh - the line straddles the edge.
        for (i in 0 until n) {
            val solid = (pix[i] ushr 24) >= MESH_ALPHA_CUTOFF
            val dist = if (solid) dIn[i] else dOut[i]
            if (dist <= 0 || dist >= BIG) continue   // wrong side / unreached
            val t = dist / 3f
            val reach = if (solid) RING_IN_TEXELS else RING_OUT_TEXELS
            if (t > reach + 1f) continue
            val ra = (255f * ((reach + 1f - t) / reach)).toInt().coerceIn(0, 255)
            if (ra > 0) ring[i] = (ra shl 24) or 0x00FFFFFF
        }
    }

    /**
     * Erases noise-speckle components (below SPECKLE_FRACTION of the largest
     * component, floor MIN_SPECKLE_PX) from the mesh [pix] - speckles must
     * not tint anything regardless of the outline toggle, and the outline
     * band is derived from the mesh AFTER this pass, so erased speckles
     * never grow an outline either. Two cheap sweeps over the ~16k-texel
     * mask (background thread): pass 1 finds the largest component's size,
     * pass 2 zeroes every component below the floor. Uses caller-provided
     * scratch (stack/seen/comp) - no allocation per detection.
     */
    private fun removeSpeckles(pix: IntArray, w: Int, h: Int,
        stack: IntArray, seen: BooleanArray, comp: IntArray,
    ) {
        val n = w * h
        val solid: (Int) -> Boolean = { j -> (pix[j] ushr 24) >= MESH_ALPHA_CUTOFF }

        // Pass 1: largest component size.
        java.util.Arrays.fill(seen, false)
        var maxPx = 0
        for (start in 0 until n) {
            if (!solid(start) || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }
            if (cc > maxPx) maxPx = cc
        }
        val floor = maxOf(MIN_SPECKLE_PX, (maxPx * SPECKLE_FRACTION).toInt())
        if (maxPx < floor) return

        // Pass 2: erase every component below the floor (mesh AND band).
        java.util.Arrays.fill(seen, false)
        for (start in 0 until n) {
            if (!solid(start) || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (solid(k) && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }
            if (cc < floor) {
                for (i in 0 until cc) pix[comp[i]] = 0
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
