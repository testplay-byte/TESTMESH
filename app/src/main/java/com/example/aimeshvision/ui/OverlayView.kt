package com.example.aimeshvision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.aimeshvision.inference.Detection
import com.example.aimeshvision.util.Perf
import android.graphics.PorterDuffXfermode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Full-screen canvas that maps normalized detections onto screen space.
 *
 * ARCHITECTURE NOTE (contour tracing): the outline is a TRUE boundary
 * contour of each connected mask blob, obtained by:
 *
 *  1. 4-connected component labelling of the solid mask pixels (one contour
 *     per blob - multi-hand scenes get one outline per hand);
 *  2. Moore-neighbour boundary tracing of each component, which walks EVERY
 *     border pixel in order (no sampling holes, sharp fingertips preserved);
 *  3. Douglas-Peucker simplification (~1.3 mask px) to remove the pixel
 *     staircase while keeping genuine corners;
 *  4. arc-length resampling to a FIXED point count so consecutive frames have
 *     a stable, index-aligned correspondence for temporal smoothing.
 *
 * This replaces the earlier "border dot grid" approach, which sampled only
 * odd-odd pixel coordinates and therefore MISSED most of the boundary on any
 * shallow curve (a circle yielded 48 of 228 border pixels) - the walk then
 * hit gaps it was not allowed to cross and the outline fragmented or vanished.
 *
 * Contours are closed by construction, so they are always safe clip regions.
 * The contour is drawn as a smooth midpoint-quadratic curve and used to clip
 * the tinted mask; a vibrant stroke is drawn on top. All analysis runs once
 * per frame in [setResults]; [onDraw] only draws.
 *
 * Rendering per detection:
 *  1. Smooth outline ON: tinted mesh clipped to the contour + vibrant stroke.
 *  2. Smooth outline OFF: plain tinted mesh.
 *  3. Optional bounding box + corner brackets (user toggle).
 *  4. Label chip: box top when boxes are shown; the temporally smoothed top
 *     edge of the actual silhouette when they are hidden.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val COLOR_LABEL_BG = Color.parseColor("#CC000000")
        private val COLOR_LABEL_TEXT = Color.parseColor("#FFFFFF")

        private const val BOX_STROKE_W = 4f
        private const val CORNER_LEN = 36f
        private const val CORNER_STROKE_W = 7f
        private const val LABEL_TEXT_SIZE = 30f
        private const val LABEL_PADDING = 10f
        private const val LABEL_CORNER_R = 14f
        private const val MASK_ALPHA = 110

        // Screen px added around each bbox when computing mask content rects
        // (decode feather + minor spill) for the overlap fast-path test.
        private const val CONTENT_PAD_PX = 24f

        // ── contour extraction ───────────────────────────────────────────
        // Ignore tiny blobs: below this many solid pixels a component is
        // mask noise, not an object outline.
        private const val MIN_CONTOUR_PX = 12

        // SPECKLE FILTER: proto noise produces small satellite blobs next to
        // the real object (measured on real model output: 436/403/149-px
        // speckles beside a 5810-px cat) that tint the frame as floating
        // patches. A component below this fraction of the LARGEST component
        // is dropped (and its pixels cleared from the bitmap), while genuine
        // same-size objects (two hands) stay far above it.
        private const val SPECKLE_FRACTION = 0.10f

        // Douglas-Peucker tolerance in MASK pixels. Kept tight: the decode
        // blur already removes the staircase, and ONE mask px is ~8 screen px
        // on a phone, so a loose tolerance visibly cuts sharp tips away from
        // the mesh (measured: 1.3 eps + resampling let boundaries poke
        // ~15 screen px past a 5px stroke).
        private const val DP_EPSILON = 0.7f

        // Every contour is resampled to this many points by arc length, so
        // consecutive frames index-align for smooth temporal blending and the
        // path cost is bounded regardless of mask size. 144 (not 72): at
        // ~8 screen px per mask px, 72-point sampling spaced tips ~6 screen
        // px off the true apex - the line visibly cut through fingertips.
        private const val CONTOUR_POINTS = 144

        // Corner-aware smoothing: turning angles at or above this (degrees)
        // mark a genuine corner (fingertip, notch, box corner) and are locked;
        // all other points are damped toward their neighbours' midpoint.
        private const val CORNER_ANGLE_DEG = 50.0
        private const val CORNER_SMOOTH_K = 0.35f
        private const val CORNER_SMOOTH_ITERS = 2

        private const val LINE_STROKE_W = 5f     // screen px (old look)

        // ── temporal smoothing (dot EMA + label anchor) ──────────────────
        private const val SMOOTHING = 0.45f
        private const val MAX_TRACK_DISTANCE = 0.35f // normalized; beyond = re-acquire

        // Instances survive this many setResults() calls without a match
        // (frame-count grace - wall-clock timeouts broke smoothing on slow
        // frames).
        private const val GRACE_FRAMES = 3L

        // Stage 2C: chains beyond the primary that get EMA tracks, and the
        // first-dot proximity gate (normalized) guarding every correspondence.
        private const val MAX_SECONDARY_EMA = 2   // primary + 2 secondary = top 3
        // First-point proximity gate guarding EMA correspondence. With the
        // anchor-stable pipeline index 0 is the same physical point every
        // frame; IoU instance matching already allows ~0.10-0.15 normalized
        // displacement per frame, so this gate must be LOOSE enough not to
        // trip on normal fast motion (each trip used to pop the outline to
        // raw for a frame - the residual jitter source). A failed gate with
        // matching length still blends, at reduced weight (emaSoft rawBias).
        private const val FIRST_DOT_GATE = 0.12f

        // Weight shift toward raw when the gate misses but correspondence is
        // still structurally valid (same length on an IoU-matched instance).
        // 0.5 keeps meaningful smoothing while staying responsive enough that
        // the track re-converges within a frame or two - no pop-to-raw.
        private const val RAW_BIAS_ON_GATE_MISS = 0.5f
    }

    /** A detection with its per-frame geometry precomputed in [setResults]. */
    private class PreparedItem(
        val det: Detection,
        /** One outline per chain, normalized 0..1 in mask space (may be empty). */
        val chains: List<List<Pair<Float, Float>>>,
    )

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BOX_STROKE_W
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CORNER_STROKE_W
        strokeCap = Paint.Cap.ROUND
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_STROKE_W
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL_BG
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL_TEXT
        textSize = LABEL_TEXT_SIZE
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }

    private var prepared: List<PreparedItem> = emptyList()
    private var sourceImageWidth: Int = 640
    private var sourceImageHeight: Int = 640

    /** User-toggleable rendering options (settings sheet). */
    var showBoxes: Boolean = true
    var showSmoothOutline: Boolean = false

    // ── temporal state (per DETECTION INSTANCE, matched by IoU) ──────────────
    // Per-class tracks were the root cause of the six-hands bug: two hands of
    // the same class shared one track, so their dots blended into a single
    // outline (and the clip built from it ate the mesh). Instances are now
    // matched to the previous frame by bounding-box IoU and smoothed per
    // instance; unmatched detections render raw.
    private class Track(
        val points: Array<Pair<Float, Float>>,
    )

    private class InstanceTrack(
        var box: android.graphics.RectF,   // previous frame's bbox (updated on match)
        val classId: Int,
        var track: Track?,                 // longest chain's track (primary)
        var lastFrame: Long,               // setResults() counter at last match
        // Stage 2C: secondary blob tracks, same order as the sorted chain
        // list (descending size). Null = no stable correspondence yet.
        var secondary: MutableList<Track?> = mutableListOf(),
    )

    private val instances = ArrayList<InstanceTrack>()
    private var frameCounter = 0L

    /** Called once per inference result: precomputes geometry, then redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        val perfT = Perf.start()
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight

        frameCounter++

        // PERF GATE: contours (flood fill, trace, DP, resample, EMA) are only
        // ever consumed by the outline. With the outline off they were still
        // running for every detection on the MAIN thread every frame - the
        // biggest avoidable UI cost. Skip all of it and reset temporal state
        // so re-enabling the outline starts clean (one raw frame, then EMA).
        if (!showSmoothOutline) {
            instances.clear()
            val plain = ArrayList<PreparedItem>(results.size)
            for (det in results) plain.add(PreparedItem(det, emptyList()))
            prepared = plain
            invalidate()
            return
        }

        // Match current detections to previous-frame instances by IoU.
        val matched = BooleanArray(instances.size)
        val assignments = HashMap<Detection, InstanceTrack?>(results.size)
        for (det in results) {
            var bestIdx = -1
            var bestIou = 0.25f   // below this = not the same object
            for (i in instances.indices) {
                if (matched[i]) continue
                val inst = instances[i]
                if (inst.classId != det.classId) continue
                val iou = iouOf(inst.box, det.boundingBox)
                if (iou > bestIou) { bestIou = iou; bestIdx = i }
            }
            if (bestIdx >= 0) {
                matched[bestIdx] = true
                assignments[det] = instances[bestIdx]
            } else {
                assignments[det] = null
            }
        }

        val items = ArrayList<PreparedItem>(results.size)
        for (det in results) {
            val mask = det.maskBitmap
            var chains: List<List<Pair<Float, Float>>> = emptyList()

            // Any matched detection keeps its instance alive, even when mask
            // decode failed this frame (prevents EMA state reset flicker).
            assignments[det]?.let { it.lastFrame = frameCounter }

            if (mask != null) {
                // One closed contour per connected blob, normalized 0..1,
                // sorted biggest-first.
                val normContours = extractContours(mask)
                if (normContours.isNotEmpty()) {
                    val inst = assignments[det]
                    val outContours = ArrayList<List<Pair<Float, Float>>>(normContours.size)
                    // Secondary tracks for the NEXT frame, in current chain
                    // order (index 0 = primary).
                    val nextTracks = ArrayList<Track?>(normContours.size)

                    // Primary (biggest blob): match to the instance's primary track.
                    // Correspondence outcomes, in order:
                    //  - no prev track / structural length change -> raw
                    //  - length matches + within gate -> full soft EMA
                    //  - length matches but outside gate (fast motion on an
                    //    IoU-matched instance) -> soft EMA with reduced weight,
                    //    so the line never pops to raw and the track recovers
                    //    next frame instead of resetting.
                    val primary = normContours[0]
                    val primaryPrev = inst?.track
                    val primaryLenMatch = primaryPrev != null &&
                        primaryPrev.points.size == primary.size
                    val primaryGateOk = primaryLenMatch &&
                        firstDotClose(primaryPrev!!.points, primary)
                    val primarySmoothed = when {
                        !primaryLenMatch -> primary
                        primaryGateOk -> emaSoft(primaryPrev!!, primary)
                        else -> emaSoft(primaryPrev!!, primary, RAW_BIAS_ON_GATE_MISS)
                    }
                    outContours.add(primarySmoothed)
                    nextTracks.add(Track(primarySmoothed.toTypedArray()))

                    // Secondary blobs: match each to the nearest UNUSED previous
                    // secondary track by first-point distance (NOT by sorted
                    // index - two similar blobs can swap size order between
                    // frames, which would blend one blob's track into another).
                    val prevSec = inst?.secondary
                    val secUsed = BooleanArray(prevSec?.size ?: 0)
                    for (ci in 1 until normContours.size) {
                        val chain = normContours[ci]
                        var bestK = -1
                        var bestD = FIRST_DOT_GATE
                        if (prevSec != null) {
                            for (k in prevSec.indices) {
                                if (secUsed[k]) continue
                                val t = prevSec[k] ?: continue
                                if (t.points.size != chain.size) continue
                                val d = firstDotDist(t.points, chain)
                                if (d <= bestD) { bestD = d; bestK = k }
                            }
                        }
                        val prev = if (bestK >= 0) prevSec!![bestK] else null
                        if (bestK >= 0) secUsed[bestK] = true
                        val correspondable = prev != null && firstDotClose(prev.points, chain)
                        val smoothed = when {
                            prev == null -> chain
                            correspondable -> emaSoft(prev, chain)
                            // Matched but outside the gate: reduced-weight
                            // blend instead of a pop to raw (same policy as
                            // the primary path).
                            else -> emaSoft(prev, chain, RAW_BIAS_ON_GATE_MISS)
                        }
                        outContours.add(smoothed)
                        if (ci <= MAX_SECONDARY_EMA) {
                            nextTracks.add(Track(smoothed.toTypedArray()))
                        }
                    }
                    chains = outContours

                    if (inst != null) {
                        inst.box = det.boundingBox
                        inst.lastFrame = frameCounter
                        inst.track = nextTracks.firstOrNull()
                        inst.secondary.clear()
                        for (k in 1 until nextTracks.size) inst.secondary.add(nextTracks[k])
                    } else {
                        val newInst = InstanceTrack(
                            det.boundingBox, det.classId, nextTracks.firstOrNull(), frameCounter,
                        )
                        for (k in 1 until nextTracks.size) newInst.secondary.add(nextTracks[k])
                        instances.add(newInst)
                    }
                } else if (assignments[det] != null) {
                    // Mask present but no usable contour: keep the instance
                    // alive so smoothing survives one bad frame.
                    assignments[det]!!.lastFrame = frameCounter
                }
            }
            items.add(PreparedItem(det, chains))
        }

        // Expire instances not seen for GRACE_FRAMES consecutive setResults
        // calls (wall-clock timeouts silently disabled smoothing on slow
        // frames - reviewer issue #6).
        instances.removeAll { frameCounter - it.lastFrame > GRACE_FRAMES }
        prepared = items
        invalidate()
        Perf.log("overlay-setResults", perfT)
    }

    fun clear() {
        prepared = emptyList()
        invalidate()
    }

    /** Forgets temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        instances.clear()
        labelCache.clear()
        prepared = emptyList()
        invalidate()
    }

    // ── contour extraction ──────────────────────────────────────────────

    // Reusable per-frame scratch for the mask-sized buffers. The mask is
    // ~128x128 and these run per detection per frame, so allocating them fresh
    // caused steady young-gen garbage (GC jank at camera frame rates). They
    // are grown only when the mask size changes.
    private var scratchN = 0
    private var pxScratch = IntArray(0)
    private var solidScratch = BooleanArray(0)
    private var seenScratch = BooleanArray(0)
    private var markScratch = BooleanArray(0)
    private var stackScratch = IntArray(0)
    private var compScratch = IntArray(0)
    private var dpScratch = IntArray(0)

    /**
     * Extracts boundary contours from a decoded mask: one per connected solid
     * blob, each a closed polygon of normalized (0..1) mask-space points with
     * a FIXED length ([CONTOUR_POINTS]) for stable temporal correspondence.
     *
     * Pipeline per blob: 4-connected labelling -> Moore-neighbour border trace
     * (visits every border pixel, so fingertips and sharp tips are exact) ->
     * Douglas-Peucker simplification (kills the pixel staircase) -> canonical
     * winding + start point -> arc-length resample. Index 0 is then exactly the
     * same boundary point (topmost-then-leftmost) in every frame; index i is
     * within the small arc-length drift caused by perimeter change. Returns an
     * empty list when the mask has no usable blob.
     */
    private fun extractContours(mask: Bitmap): List<List<Pair<Float, Float>>> {
        val w = mask.width
        val h = mask.height
        if (w < 4 || h < 4) return emptyList()
        val n = w * h

        if (scratchN < n) {
            scratchN = n
            pxScratch = IntArray(n)
            solidScratch = BooleanArray(n)
            seenScratch = BooleanArray(n)
            markScratch = BooleanArray(n)
            stackScratch = IntArray(n)
            compScratch = IntArray(n)
        }
        val px = pxScratch
        val solid = solidScratch
        val seen = seenScratch
        val mark = markScratch
        val stack = stackScratch
        val comp = compScratch

        mask.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            solid[i] = (px[i] ushr 24) > 128
            seen[i] = false
            mark[i] = false
        }

        // Contours paired with their blob area, so the biggest blob can be
        // sorted to index 0 (the "primary" outline for label anchoring).
        val out = ArrayList<Pair<Int, List<Pair<Float, Float>>>>()

        // PASS 1 - component sizes only (flood fills are cheap on ~16k px);
        // needed BEFORE tracing so the speckle filter knows the largest blob.
        var maxComp = 0
        for (start in 0 until n) {
            if (!solid[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }
            if (cc > maxComp) maxComp = cc
            for (i in 0 until cc) seen[comp[i]] = false   // reset for pass 2
        }
        val speckleFloor = maxOf(MIN_CONTOUR_PX, (maxComp * SPECKLE_FRACTION).toInt())
        var clearedMask = false

        for (start in 0 until n) {
            if (!solid[start] || seen[start]) continue

            // 4-connected flood fill of one blob.
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cc = 0
            while (sp > 0) {
                val j = stack[--sp]
                comp[cc++] = j
                mark[j] = true
                val x = j % w
                val y = j / w
                if (x > 0) { val k = j - 1; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (x < w - 1) { val k = j + 1; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y > 0) { val k = j - w; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
                if (y < h - 1) { val k = j + w; if (solid[k] && !seen[k]) { seen[k] = true; stack[sp++] = k } }
            }

            if (cc < speckleFloor) {
                // Speckle/noise blob: remove its pixels from the rendered
                // bitmap too, so it never shows as a floating tint patch.
                for (i in 0 until cc) {
                    val j = comp[i]
                    mark[j] = false
                    px[j] = 0
                    clearedMask = true
                }
                continue
            }

            val contour = traceBorder(mark, w, h, comp, cc)
            // Clear the membership marks for the next blob.
            for (i in 0 until cc) mark[comp[i]] = false
            if (contour == null || contour.size < 6) continue

            // Winding fix ONLY, preserving index 0: the trace start is the
            // topmost-leftmost boundary pixel - the most stable anchor a
            // frame-to-frame correspondence can have.
            val anchored = canonicalize(contour)

            // DP split AT the anchor (its endpoints survive exactly), so the
            // resampling lattice below always starts from the same physical
            // point - no rotation ever moves index 0 again.
            val simplified = simplifyAnchored(anchored, DP_EPSILON)
            if (simplified.size < 3) continue

            val resampled = resampleClosed(simplified, CONTOUR_POINTS)
            if (resampled.size < 3) continue

            // Corner-aware smoothing: damp the residual micro-wiggle (the
            // "jittery" look) while locking genuine corners (fingertips,
            // notches, box corners) so sharp turns stay sharp.
            val smoothed = cornerSmooth(resampled)

            // Store normalized to 0..1 mask space BEFORE any screen mapping.
            out.add(cc to smoothed.map { p ->
                (p.first / mask.width.toFloat()) to (p.second / mask.height.toFloat())
            })
        }

        // Persist speckle removal (once, only when something was dropped).
        if (clearedMask) {
            mask.setPixels(px, 0, w, 0, 0, w, h)
        }

        // Biggest blob first: index 0 is the primary outline.
        out.sortByDescending { it.first }
        return out.map { it.second }
    }

    /**
     * Normalizes contour winding to positive signed area while PRESERVING
     * index 0 (the trace's topmost-leftmost anchor). Reversing flips the
     * anchor to the end, so the reversed list is rotated by one to put it
     * back at index 0. The anchor must never move: it is the temporal
     * correspondence origin for every later frame.
     */
    private fun canonicalize(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val n = pts.size
        if (n < 3) return pts
        var area2 = 0f
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            area2 += a.first * b.second - b.first * a.second
        }
        if (area2 >= 0f) return pts
        val r = pts.reversed()          // anchor (pts[0]) now sits at index n-1
        return r.subList(n - 1, n) + r.subList(0, n - 1)
    }

    /**
     * Corner-preserving smoothing of the resampled ring: each point's turning
     * angle is measured; angles >= [CORNER_ANGLE_DEG] mark a GENUINE corner
     * and are locked, everything else is pulled toward its neighbours'
     * midpoint ([CORNER_SMOOTH_K], [CORNER_SMOOTH_ITERS] passes). High-
     * frequency wiggle damps away in two passes while slow, real curvature
     * is untouched - the line reads smooth but still turns hard where the
     * object actually does (fingertips, notches, right-angle corners).
     */
    private fun cornerSmooth(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val n = pts.size
        if (n < 8) return pts

        val corner = BooleanArray(n)
        for (i in 0 until n) {
            val p0 = pts[(i - 1 + n) % n]
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val v1x = p1.first - p0.first
            val v1y = p1.second - p0.second
            val v2x = p2.first - p1.first
            val v2y = p2.second - p1.second
            val l1 = sqrt(v1x * v1x + v1y * v1y)
            val l2 = sqrt(v2x * v2x + v2y * v2y)
            if (l1 < 1e-6f || l2 < 1e-6f) continue
            val dot = ((v1x * v2x + v1y * v2y) / (l1 * l2)).toDouble().coerceIn(-1.0, 1.0)
            if (Math.toDegrees(kotlin.math.acos(dot)) >= CORNER_ANGLE_DEG) corner[i] = true
        }

        var cur = pts
        repeat(CORNER_SMOOTH_ITERS) {
            val out = ArrayList<Pair<Float, Float>>(n)
            for (i in 0 until n) {
                val p = cur[i]
                if (corner[i]) { out.add(p); continue }
                val prev = cur[(i - 1 + n) % n]
                val next = cur[(i + 1) % n]
                val mx = (prev.first + next.first) / 2f
                val my = (prev.second + next.second) / 2f
                out.add(p.first + (mx - p.first) * CORNER_SMOOTH_K to
                    p.second + (my - p.second) * CORNER_SMOOTH_K)
            }
            cur = out
        }
        return cur
    }

    /** 8-neighbour offsets in clockwise order (E, SE, S, SW, W, NW, N, NE). */
    private val neighbor8 = arrayOf(
        1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
    )

    /**
     * Moore-neighbour boundary tracing of one solid component (membership
     * flagged in [mark]). Starts at the topmost-leftmost pixel, walks
     * clockwise, and returns the ordered contour pixel list (the start pixel
     * is not repeated at the end). Returns null if tracing degenerates
     * (defensive - never throws).
     */
    private fun traceBorder(
        mark: BooleanArray, w: Int, h: Int, comp: IntArray, cc: Int,
    ): List<Pair<Float, Float>>? {
        // Start = topmost row, then leftmost column in that row.
        var minY = Int.MAX_VALUE
        for (i in 0 until cc) { val y = comp[i] / w; if (y < minY) minY = y }
        var startX = Int.MAX_VALUE
        for (i in 0 until cc) { val j = comp[i]; if (j / w == minY) { val x = j % w; if (x < startX) startX = x } }

        val contour = ArrayList<Pair<Float, Float>>(cc)
        // Pixel CENTERS (start pixel is (startX,minY) -> +0.5): contour
        // through integer corners sits 0.5 mask px off the true boundary
        // (~4 screen px at phone scale) - half a stroke width of error.
        contour.add(startX + 0.5f to minY + 0.5f)

        // Backtrack starts at the background pixel west of the start.
        var bx = startX - 1
        var by = minY
        var cx = startX
        var cy = minY
        var guard = 0
        val maxSteps = 8 * cc + 64

        while (guard++ < maxSteps) {
            val bdx = bx - cx
            val bdy = by - cy
            var bi = 0
            for (i in neighbor8.indices) {
                if (neighbor8[i].first == bdx && neighbor8[i].second == bdy) { bi = i; break }
            }
            var found = false
            for (k in 1..8) {
                val di = (bi + k) % 8
                val nx = cx + neighbor8[di].first
                val ny = cy + neighbor8[di].second
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if (!mark[ny * w + nx]) continue
                // Backtrack = the cell we scanned just before this neighbour.
                val pb = (bi + k - 1) % 8
                bx = cx + neighbor8[pb].first
                by = cy + neighbor8[pb].second
                cx = nx
                cy = ny
                found = true
                break
            }
            if (!found) break
            if (cx == startX && cy == minY) break   // closed the loop
            contour.add(cx + 0.5f to cy + 0.5f)
        }
        return contour
    }

    /** Perpendicular distance from p to segment ab. */
    private fun perpDist(
        p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>,
    ): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        if (dx == 0f && dy == 0f) return sqrt((p.first - a.first) * (p.first - a.first) +
            (p.second - a.second) * (p.second - a.second))
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / (dx * dx + dy * dy))
            .coerceIn(0f, 1f)
        val projX = a.first + t * dx
        val projY = a.second + t * dy
        return sqrt((p.first - projX) * (p.first - projX) + (p.second - projY) * (p.second - projY))
    }

    /** Iterative Douglas-Peucker on an open polyline. */
    private fun simplifyOpen(
        pts: List<Pair<Float, Float>>, eps: Float,
    ): List<Pair<Float, Float>> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        // Reusable primitive stack of (lo,hi) frames. Each split pops one frame
        // and pushes two (net +1); with at most n-1 splits and one seed frame,
        // the depth never exceeds n frames = 2n ints - sized 2n+8 for safety.
        if (dpScratch.size < pts.size * 2 + 8) dpScratch = IntArray(pts.size * 2 + 8)
        val dp = dpScratch
        var top = 0
        dp[top++] = 0
        dp[top++] = pts.size - 1
        while (top > 0) {
            val hi = dp[--top]
            val lo = dp[--top]
            if (hi <= lo + 1) continue
            var dmax = 0f
            var idx = -1
            for (i in lo + 1 until hi) {
                val d = perpDist(pts[i], pts[lo], pts[hi])
                if (d > dmax) { dmax = d; idx = i }
            }
            if (dmax > eps && idx > 0) {
                keep[idx] = true
                dp[top++] = lo
                dp[top++] = idx
                dp[top++] = idx
                dp[top++] = hi
            }
        }
        val out = ArrayList<Pair<Float, Float>>()
        for (i in pts.indices) if (keep[i]) out.add(pts[i])
        return out
    }

    /**
     * Douglas-Peucker for a CLOSED ring, split at the anchor (index 0): the
     * ring is cut open at pts[0], simplified as an open polyline with BOTH
     * endpoints pinned to that anchor, then the duplicated closing point is
     * dropped. Unlike a rotation-based split, the anchor vertex survives
     * exactly, so temporal correspondence never re-phases between frames.
     */
    private fun simplifyAnchored(
        ring: List<Pair<Float, Float>>, eps: Float,
    ): List<Pair<Float, Float>> {
        if (ring.size < 5) return ring
        val loop = ArrayList<Pair<Float, Float>>(ring.size + 1)
        loop.addAll(ring)
        loop.add(ring[0])                       // close explicitly at the anchor
        val half = simplifyOpen(loop, eps)
        if (half.size < 3) return ring
        return half.subList(0, half.size - 1)   // drop the duplicated anchor
    }

    /** Resamples a closed polygon to exactly [count] points by arc length. */
    private fun resampleClosed(
        pts: List<Pair<Float, Float>>, count: Int,
    ): List<Pair<Float, Float>> {
        val n = pts.size
        if (n < 3) return pts
        val seg = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = pts[j].first - pts[i].first
            val dy = pts[j].second - pts[i].second
            seg[i] = sqrt(dx * dx + dy * dy)
            total += seg[i]
        }
        if (total <= 0f) return pts
        val out = ArrayList<Pair<Float, Float>>(count)
        val step = total / count
        var segIdx = 0
        var segPos = 0f
        for (k in 0 until count) {
            val target = k * step
            while (segIdx < n - 1 && segPos + seg[segIdx] < target) {
                segPos += seg[segIdx]
                segIdx++
            }
            val t = if (seg[segIdx] <= 0f) 0f else (target - segPos) / seg[segIdx]
            val a = pts[segIdx]
            val b = pts[(segIdx + 1) % n]
            out.add(a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t)
        }
        return out
    }

    // ── temporal smoothing ────────────────────────────────────────────────────

    /**
     * EMA-blends the contour points with the previous frame's track. Both
     * contours are canonicalized (same winding, same start point) and have the
     * same fixed point count, so index 0 maps to the exact same boundary point
     * in both frames and index i is within the small arc-length drift from
     * perimeter change. A size change falls back to the raw contour.
     */
    /**
     * EMA-blends the contour points with the previous frame's track. Both
     * contours are canonicalized (same winding, same start point) and have the
     * same fixed point count, so index 0 maps to the exact same boundary point
     * in both frames and index i is within the small arc-length drift from
     * perimeter change. A size change falls back to the raw contour.
     *
     * [rawBias] shifts the final blend toward raw (0 = normal soft gate,
     * 1 = fully raw). Used when the first-point gate misses on a structurally
     * valid correspondence: smoothing is reduced but never dropped, so the
     * outline does not pop to raw for a frame (the old binary behaviour).
     */
    private fun emaSoft(
        prev: Track, rawIn: List<Pair<Float, Float>>, rawBias: Float = 0f,
    ): List<Pair<Float, Float>> {
        if (prev.points.size != rawIn.size) {
            return rawIn
        }

        // Soft per-dot gate: blend weight fades from full smoothing at d=0 to
        // raw at d=MAX_TRACK_DISTANCE. Small changes stay smooth; large
        // changes pass through instead of popping.
        val out = ArrayList<Pair<Float, Float>>(rawIn.size)
        for (i in rawIn.indices) {
            val px = prev.points[i].first
            val py = prev.points[i].second
            val nx = rawIn[i].first
            val ny = rawIn[i].second
            val dx = nx - px
            val dy = ny - py
            val d = sqrt(dx * dx + dy * dy)
            // weight on the NEW position: 0 (full smoothing) .. 1 (raw)
            val rawWeight = (d / MAX_TRACK_DISTANCE).coerceIn(0f, 1f)
            var blend = rawWeight + (1f - rawWeight) * (1f - SMOOTHING)
            if (rawBias > 0f) blend += (1f - blend) * rawBias
            out.add(px + dx * blend to py + dy * blend)
        }
        return out
    }

    /** IoU of two normalized bounding boxes (instance matching). */
    private fun iouOf(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ── drawing (cached geometry only) ────────────────────────────────────────

    // Offscreen mesh compositor. Masks are drawn into this buffer at FULL
    // alpha (colour baked per class), then the whole buffer is blitted once
    // at MASK_ALPHA. SRC_OVER of opaque same-colour pixels is idempotent, so
    // overlapping instances can no longer compound 110 -> 163 -> 197 toward
    // opacity - the "mesh turns solid with ten hands" bug. Allocated lazily,
    // screen-sized, reused across frames.
    private var meshBitmap: Bitmap? = null
    private var meshCanvas: Canvas? = null
    private val meshBlitPaint = Paint()
    /** Integer source rect scratch for the dirty-rect blit. */
    private val blitSrcRect = Rect()

    /** Reused clip path: view rect minus all filled mesh contours. */
    private val outsideClipPath = Path()

    /** Paint used to CLEAR only the dirty rect of the composite buffer. */
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /**
     * Returns the composite buffer, cleared ONLY inside [dirty] (perf: the
     * old version cleared the full ~10 MB screen buffer every frame even when
     * a single small object was on screen). Buffer is screen-sized, reused,
     * and recreated only on view-size change.
     */
    private fun meshBuffer(dirty: RectF): Canvas {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val existing = meshBitmap
        if (existing == null || existing.width != w || existing.height != h) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            meshBitmap = b
            meshCanvas = Canvas(b)
        }
        meshCanvas!!.drawRect(dirty, clearPaint)
        return meshCanvas!!
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Snapshot once: PASS 1 and PASS 3 must see the SAME list instance,
        // or pathsPerItem[i] could pair with a different item if setResults
        // ever runs off the main thread (currently main-confined by caller
        // discipline only).
        val perfDrawT = Perf.start()
        val items = prepared
        if (items.isEmpty()) return

        // fitCenter mapping between the source image and this view.
        val scaleX = width.toFloat() / sourceImageWidth
        val scaleY = height.toFloat() / sourceImageHeight
        val scale = minOf(scaleX, scaleY)
        val scaledW = sourceImageWidth * scale
        val scaledH = sourceImageHeight * scale
        val offsetX = (width - scaledW) / 2f
        val offsetY = (height - scaledH) / 2f
        val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

        // PASS 1a - build stroke paths (also the tint clip) and per-mask
        // content rects (bbox + feather margin) for the route decision.
        val pathsPerItem = ArrayList<List<Path>>(items.size)
        val contentRects = ArrayList<RectF>(items.size)
        for (item in items) {
            val mask = item.det.maskBitmap
            val paths = if (mask != null && showSmoothOutline) {
                item.chains.mapNotNull { chain ->
                    if (chain.size >= 3) buildSmoothPath(chain, maskRect) else null
                }
            } else emptyList()
            pathsPerItem.add(paths)
            if (mask == null) continue
            val det = item.det
            contentRects.add(RectF(
                det.boundingBox.left * scaledW + offsetX - CONTENT_PAD_PX,
                det.boundingBox.top * scaledH + offsetY - CONTENT_PAD_PX,
                det.boundingBox.right * scaledW + offsetX + CONTENT_PAD_PX,
                det.boundingBox.bottom * scaledH + offsetY + CONTENT_PAD_PX,
            ))
        }

        // PASS 1b - ROUTE DECISION. Alpha compounding only happens where two
        // masks OVERLAP; a non-overlapping set drawn directly at MASK_ALPHA is
        // pixel-equivalent to the composite result, so the offscreen buffer
        // (10 MB clear + full-screen blit every frame) is only paid for when
        // it is needed - the "many objects crowd together" case.
        val overlaps = haveOverlap(contentRects)
        if (contentRects.isEmpty() || !overlaps) {
            // FAST PATH - direct translucent draws, zero offscreen work.
            for (i in items.indices) {
                val mask = items[i].det.maskBitmap ?: continue
                val classColor = DetectionStyle.colorFor(items[i].det.classId)
                drawTintClipped(canvas, mask, maskRect, classColor, pathsPerItem[i])
            }
        } else {
            // COMPOSITE PATH - union blit at MASK_ALPHA: overlap regions can
            // never compound toward opacity (the ten-hands solid-mesh bug).
            val dirty = unionRect(contentRects)
            if (!dirty.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                // nothing visible on screen
            } else {
                val bc = meshBuffer(dirty)
                bc.clipRect(dirty)
                for (i in items.indices) {
                    val mask = items[i].det.maskBitmap ?: continue
                    val classColor = DetectionStyle.colorFor(items[i].det.classId)
                    drawTintClipped(bc, mask, maskRect, classColor, pathsPerItem[i],
                        fullAlpha = true)
                }
                meshBlitPaint.alpha = MASK_ALPHA
                blitSrcRect.set(Math.round(dirty.left), Math.round(dirty.top),
                    Math.round(dirty.right), Math.round(dirty.bottom))
                canvas.drawBitmap(meshBitmap!!, blitSrcRect, dirty, meshBlitPaint)
            }
        }

        // PASS 3a - OUTSIDE-ONLY STROKES. The stroke path follows the exact
        // mesh boundary; the canvas is clipped to "everything except the
        // filled mesh contours", so only the OUTER half of the stroke
        // survives - one continuous border hugging the outside of the mesh,
        // with no geometry distortion (and therefore no corner artifacts).
        var anyStroke = false
        for (paths in pathsPerItem) if (paths.isNotEmpty()) { anyStroke = true; break }
        if (anyStroke) {
            outsideClipPath.reset()
            outsideClipPath.addRect(-4f, -4f, width + 4f, height + 4f,
                Path.Direction.CW)
            for (paths in pathsPerItem) {
                for (p in paths) outsideClipPath.op(p, Path.Op.DIFFERENCE)
            }
            canvas.save()
            canvas.clipPath(outsideClipPath)
            for (i in items.indices) {
                val paths = pathsPerItem[i]
                if (paths.isEmpty()) continue
                val classColor = DetectionStyle.colorFor(items[i].det.classId)
                linePaint.color = DetectionStyle.vibrantFor(classColor)
                linePaint.alpha = 255
                for (p in paths) canvas.drawPath(p, linePaint)
            }
            canvas.restore()
        }

        // PASS 3b - boxes, corners, labels.
        for (i in items.indices) {
            val item = items[i]
            val det = item.det
            val classColor = DetectionStyle.colorFor(det.classId)

            val left = det.boundingBox.left * scaledW + offsetX
            val top = det.boundingBox.top * scaledH + offsetY
            val right = det.boundingBox.right * scaledW + offsetX
            val bottom = det.boundingBox.bottom * scaledH + offsetY
            val screenRect = RectF(left, top, right, bottom)

            // Labels are ALWAYS anchored to the box top - with boxes hidden
            // the chip shows exactly where it would appear with boxes shown
            // (the old silhouette-anchor placement here was removed on user
            // request; boundingBox is available even when the box is not
            // drawn).
            if (showBoxes) {
                boxPaint.color = classColor
                boxPaint.alpha = 190
                canvas.drawRect(screenRect, boxPaint)
                drawCorners(canvas, screenRect, classColor)
            }
            drawLabel(canvas, screenRect, det, classColor, anchorTop = screenRect.top)
        }
        Perf.log("overlay-onDraw", perfDrawT)
    }

    /** True when any two rects intersect (the alpha-compounding condition). */
    private fun haveOverlap(rects: List<RectF>): Boolean {
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            if (RectF.intersects(rects[i], rects[j])) return true
        }
        return false
    }

    /** Bounding union of a non-empty rect list (new instance). */
    private fun unionRect(rects: List<RectF>): RectF {
        val u = RectF(rects[0])
        for (i in 1 until rects.size) u.union(rects[i])
        return u
    }

    /**
     * Draws one mask tinted with the class colour, optionally clipped to its
     * outline paths (tint stays inside the outline when one exists; clipPath
     * calls INTERSECT so each path gets save/clip/draw/restore).
     *
     * @param fullAlpha true = write at full alpha for the composite route,
     *        which applies MASK_ALPHA once at blit time; false = apply
     *        MASK_ALPHA directly (fast route).
     */
    private fun drawTintClipped(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
        paths: List<Path>, fullAlpha: Boolean = false,
    ) {
        maskPaint.colorFilter = colorFilterFor(classColor)
        maskPaint.alpha = if (fullAlpha) 255 else MASK_ALPHA
        val sane = paths.filter { isSaneClipPath(it, maskRect) }
        if (sane.isEmpty()) {
            canvas.drawBitmap(mask, null, maskRect, maskPaint)
            return
        }
        for (p in sane) {
            val save = canvas.save()
            canvas.clipPath(p)
            canvas.drawBitmap(mask, null, maskRect, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    /** Distance (normalized) between the first contour points of two contours. */
    private fun firstDotDist(
        prev: Array<Pair<Float, Float>>, cur: List<Pair<Float, Float>>,
    ): Float {
        val dx = prev[0].first - cur[0].first
        val dy = prev[0].second - cur[0].second
        return sqrt(dx * dx + dy * dy)
    }

    /** True when the first points of two contours are within FIRST_DOT_GATE. */
    private fun firstDotClose(
        prev: Array<Pair<Float, Float>>, cur: List<Pair<Float, Float>>,
    ): Boolean = firstDotDist(prev, cur) <= FIRST_DOT_GATE

    private val filterCache = HashMap<Int, android.graphics.ColorFilter>()

    /**
     * A clip path is sane when its bounds are real (nonzero) and inside the
     * mask rect. Calibration vs the path's OWN bounds - not the frame-sized
     * mask rect (a hand is usually <15% of the frame, which made the v10
     * check reject every valid outline).
     */
    private fun isSaneClipPath(path: Path, maskRect: RectF): Boolean {
        val b = android.graphics.RectF()
        path.computeBounds(b, true)
        if (b.isEmpty || b.width() <= 1f || b.height() <= 1f) return false
        // Path bounds must sit inside the mask rect (with a small tolerance).
        return b.left >= maskRect.left - 2f && b.top >= maskRect.top - 2f &&
            b.right <= maskRect.right + 2f && b.bottom <= maskRect.bottom + 2f
    }

    private fun colorFilterFor(color: Int): android.graphics.ColorFilter =
        filterCache.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    /** Class id -> uppercase label (avoids per-frame string churn). */
    private val labelCache = HashMap<Int, String>()

    /**
     * Builds the smooth CLOSED path through the resampled contour: on-curve
     * midpoints with the contour points as quadratic control points, so each
     * contour point is represented exactly while the line between them is
     * smooth. Contours are closed by construction, so the path always closes.
     */
    private fun buildSmoothPath(
        dots: List<Pair<Float, Float>>, maskRect: RectF,
    ): Path {
        val n = dots.size
        if (n < 3) return Path()

        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2] = maskRect.left + dots[i].first * maskRect.width()
            pts[i * 2 + 1] = maskRect.top + dots[i].second * maskRect.height()
        }

        // The stroke is made to sit OUTSIDE the mesh by CLIPPING it to the
        // complement of the filled contour at draw time (see PASS 3), NOT by
        // moving these points: offsetting each point along its own normal
    // makes neighbouring normals cross at sharp corners, and the crossed
        // polyline rendered as duplicated/looped line segments - the double
        // line artifacts seen at edges. Keeping the geometry pristine and
        // clipping instead is artifact-free by construction.

        val mids = FloatArray(n * 2)
        for (i in 0 until n) {
            val j = (i + 1) % n
            mids[i * 2] = (pts[i * 2] + pts[j * 2]) / 2f
            mids[i * 2 + 1] = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
        }

        val path = Path()
        path.moveTo(mids[0], mids[1])
        for (i in 0 until n) {
            // Control = the contour point between the two midpoint anchors, so
            // the curve bulges out to hug the real boundary point.
            path.quadTo(
                pts[((i + 1) % n) * 2], pts[((i + 1) % n) * 2 + 1],
                mids[i * 2], mids[i * 2 + 1],
            )
        }
        path.close()
        return path
    }

    private fun drawCorners(canvas: Canvas, rect: RectF, color: Int) {
        cornerPaint.color = color
        val len = CORNER_LEN.coerceAtMost(rect.width() / 2f).coerceAtMost(rect.height() / 2f)

        // top-left
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, cornerPaint)
        // top-right
        canvas.drawLine(rect.right - len, rect.top, rect.right, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, cornerPaint)
        // bottom-left
        canvas.drawLine(rect.left, rect.bottom - len, rect.left, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, cornerPaint)
        // bottom-right
        canvas.drawLine(rect.right - len, rect.bottom, rect.right, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom - len, rect.right, rect.bottom, cornerPaint)
    }

    /**
     * Draws the label chip at [anchorTop] (the smoothed top edge of the object
     * when boxes are hidden, the box top otherwise).
     */
    private fun drawLabel(
        canvas: Canvas, rect: RectF, det: Detection, classColor: Int, anchorTop: Float,
    ) {
        val confPercent = (det.confidence * 100).toInt()
        val labelUpper = labelCache.getOrPut(det.classId) { det.label.uppercase() }
        val text = "$labelUpper  $confPercent%"

        val textWidth = labelTextPaint.measureText(text)
        val chipW = textWidth + LABEL_PADDING * 2f
        val chipH = labelTextPaint.textSize + LABEL_PADDING * 2f

        val chipTop = if (anchorTop - chipH >= 0f) anchorTop - chipH else anchorTop
        val chipLeft = maxOf(0f, rect.left)

        labelTextPaint.color = Color.WHITE
        canvas.drawRoundRect(
            chipLeft, chipTop, chipLeft + chipW, chipTop + chipH,
            LABEL_CORNER_R, LABEL_CORNER_R, labelBgPaint,
        )
        canvas.drawText(
            text, chipLeft + LABEL_PADDING,
            chipTop + LABEL_PADDING + labelTextPaint.textSize,
            labelTextPaint,
        )
        labelTextPaint.color = classColor
    }
}
