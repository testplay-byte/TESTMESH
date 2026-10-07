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
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.aimeshvision.inference.Detection
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

        // ── contour extraction ───────────────────────────────────────────
        // Ignore tiny blobs: below this many solid pixels a component is
        // mask noise, not an object outline.
        private const val MIN_CONTOUR_PX = 12

        // Douglas-Peucker tolerance in MASK pixels: removes the 1px pixel
        // staircase while keeping real corners/fingertips (~1% of a 128px
        // proto mask).
        private const val DP_EPSILON = 1.3f

        // Every contour is resampled to this many points by arc length, so
        // consecutive frames index-align for smooth temporal blending and the
        // path cost is bounded regardless of mask size.
        private const val CONTOUR_POINTS = 72

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
        private const val FIRST_DOT_GATE = 0.03f
    }

    /** A detection with its per-frame geometry precomputed in [setResults]. */
    private class PreparedItem(
        val det: Detection,
        /** One outline per chain, normalized 0..1 in mask space (may be empty). */
        val chains: List<List<Pair<Float, Float>>>,
        /** Smoothed top edge of the silhouette, normalized 0..1 (null = none). */
        val labelAnchorNorm: Float?,
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
        var labelAnchor: Float?,
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
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight

        frameCounter++

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
            var anchor: Float? = null

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

                    // Primary (biggest blob): match to the instance's primary
                    // track, guarded by equal length + first-point proximity.
                    val primary = normContours[0]
                    val primaryPrev = inst?.track
                    val primaryOk = primaryPrev != null &&
                        primaryPrev.points.size == primary.size &&
                        firstDotClose(primaryPrev.points, primary)
                    val primarySmoothed = if (primaryOk) emaSoft(primaryPrev!!, primary) else primary
                    anchor = if (inst != null && primaryOk) {
                        emaAnchor(inst, primarySmoothed)
                    } else primarySmoothed.minOfOrNull { it.second }
                    outContours.add(primarySmoothed)
                    nextTracks.add(Track(primarySmoothed.toTypedArray(), null))

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
                        val correspondable = prev != null &&
                            prev.points.size == chain.size && firstDotClose(prev.points, chain)
                        val smoothed = if (correspondable) emaSoft(prev!!, chain) else chain
                        outContours.add(smoothed)
                        if (ci <= MAX_SECONDARY_EMA) {
                            nextTracks.add(Track(smoothed.toTypedArray(), null))
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
            items.add(PreparedItem(det, chains, anchor))
        }

        // Expire instances not seen for GRACE_FRAMES consecutive setResults
        // calls (wall-clock timeouts silently disabled smoothing on slow
        // frames - reviewer issue #6).
        instances.removeAll { frameCounter - it.lastFrame > GRACE_FRAMES }
        prepared = items
        invalidate()
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

            if (cc < MIN_CONTOUR_PX) {
                for (i in 0 until cc) mark[comp[i]] = false
                continue
            }

            val contour = traceBorder(mark, w, h, comp, cc)
            // Clear the membership marks for the next blob.
            for (i in 0 until cc) mark[comp[i]] = false
            if (contour == null || contour.size < 6) continue

            val simplified = simplifyClosed(contour, DP_EPSILON)
            if (simplified.size < 3) continue

            // Canonicalize BEFORE resampling: index 0 becomes exactly the same
            // physical point (topmost, then leftmost) in every frame, so the
            // arc-length lattice starts from a stable origin and index i maps
            // to the same boundary location across frames.
            val canonical = canonicalize(simplified)

            val resampled = resampleClosed(canonical, CONTOUR_POINTS)
            if (resampled.size < 3) continue

            // Store normalized to 0..1 mask space BEFORE any screen mapping.
            out.add(cc to resampled.map { p ->
                (p.first / mask.width.toFloat()) to (p.second / mask.height.toFloat())
            })
        }

        // Biggest blob first: index 0 is the primary outline.
        out.sortByDescending { it.first }
        return out.map { it.second }
    }

    /**
     * Canonicalizes a closed polygon for stable temporal correspondence:
     * reverses it to a consistent winding (positive signed area) and rotates
     * it so index 0 is the topmost-then-leftmost point. Without this, the
     * trace/resample start point drifts frame to frame and the EMA would blend
     * point i against a spatially unrelated point.
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
        var ring = if (area2 < 0f) pts.reversed() else pts
        var startIdx = 0
        for (i in 1 until n) {
            val y = ring[i].second
            val y0 = ring[startIdx].second
            if (y < y0 || (y == y0 && ring[i].first < ring[startIdx].first)) startIdx = i
        }
        if (startIdx == 0) return ring
        return ring.subList(startIdx, n) + ring.subList(0, startIdx)
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
        contour.add(startX.toFloat() to minY.toFloat())

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
            contour.add(cx.toFloat() to cy.toFloat())
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
     * Douglas-Peucker for a CLOSED polygon: rotates the ring to start at the
     * point farthest from the centroid (so both halves are non-degenerate even
     * for convex shapes), splits it in half, simplifies each half, then drops
     * only the ONE vertex duplicated at the split (A's last == B's first).
     * The ring's wrap endpoint (B's last) is a real vertex and is kept.
     */
    private fun simplifyClosed(
        ptsIn: List<Pair<Float, Float>>, eps: Float,
    ): List<Pair<Float, Float>> {
        var pts = ptsIn
        if (pts.size > 1 && pts.first() == pts.last()) pts = pts.dropLast(1)
        if (pts.size < 4) return pts
        // Split at the point farthest from the centroid (keeps both halves
        // non-degenerate even for convex shapes).
        var cxs = 0f
        var cys = 0f
        for (p in pts) { cxs += p.first; cys += p.second }
        cxs /= pts.size
        cys /= pts.size
        var farIdx = 0
        var farD = -1f
        for (i in pts.indices) {
            val dx = pts[i].first - cxs
            val dy = pts[i].second - cys
            val d = dx * dx + dy * dy
            if (d > farD) { farD = d; farIdx = i }
        }
        val rot = pts.subList(farIdx, pts.size) + pts.subList(0, farIdx)
        val half = rot.size / 2
        val a = simplifyOpen(rot.subList(0, half + 1), eps)
        val b = simplifyOpen(rot.subList(half, rot.size), eps)
        // Join: drop the single duplicated split vertex (a.last == b.first).
        val out = ArrayList<Pair<Float, Float>>(a.size + b.size)
        out.addAll(a.subList(0, a.size - 1))
        out.addAll(b)
        return out
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
    private fun emaSoft(
        prev: Track, rawIn: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        if (prev.points.size != rawIn.size) {
            return rawIn
        }

        // Stage 2D: soft per-dot gate. Correspondence is guaranteed upstream
        // (length match + canonical winding from Stage 3F), so the hard
        // chain-level snap (which made every fast move pop to raw) becomes a
        // continuous blend: blend weight fades from full smoothing at d=0 to
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
            val blend = rawWeight + (1f - rawWeight) * (1f - SMOOTHING)
            out.add(px + dx * blend to py + dy * blend)
        }
        return out
    }

    /** EMA-blends the label anchor (top edge Y, normalized) across frames. */
    private fun emaAnchor(inst: InstanceTrack, dots: List<Pair<Float, Float>>?): Float? {
        val raw = dots?.minOfOrNull { it.second } ?: return null
        val prev = inst.track?.labelAnchor
        val smoothed = if (prev == null || abs(raw - prev) > MAX_TRACK_DISTANCE) {
            raw
        } else {
            prev + (raw - prev) * (1f - SMOOTHING)
        }
        return smoothed
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (prepared.isEmpty()) return

        // fitCenter mapping between the source image and this view.
        val scaleX = width.toFloat() / sourceImageWidth
        val scaleY = height.toFloat() / sourceImageHeight
        val scale = minOf(scaleX, scaleY)
        val scaledW = sourceImageWidth * scale
        val scaledH = sourceImageHeight * scale
        val offsetX = (width - scaledW) / 2f
        val offsetY = (height - scaledH) / 2f

        for (item in prepared) {
            val det = item.det
            val classColor = DetectionStyle.colorFor(det.classId)

            val left = det.boundingBox.left * scaledW + offsetX
            val top = det.boundingBox.top * scaledH + offsetY
            val right = det.boundingBox.right * scaledW + offsetX
            val bottom = det.boundingBox.bottom * scaledH + offsetY
            val screenRect = RectF(left, top, right, bottom)

            val mask = det.maskBitmap
            if (mask != null) {
                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

                // Every contour is closed by construction, so each is a valid
                // clip region. One tinted-mesh draw per contour: successive
                // clipPath calls INTERSECT (not union), so disjoint blobs must
                // be clipped+drawn one at a time or the region collapses.
                val paths = if (showSmoothOutline) {
                    item.chains.mapNotNull { chain ->
                        if (chain.size >= 3) buildSmoothPath(chain, maskRect) else null
                    }
                } else emptyList()

                if (paths.isEmpty()) {
                    // Outline off (or no contour): plain full mask.
                    drawTintedMask(canvas, mask, maskRect, classColor)
                } else {
                    val sane = paths.filter { isSaneClipPath(it, maskRect) }
                    if (sane.isEmpty()) {
                        drawTintedMask(canvas, mask, maskRect, classColor)
                    } else {
                        for (p in sane) {
                            val save = canvas.save()
                            canvas.clipPath(p)
                            drawTintedMask(canvas, mask, maskRect, classColor)
                            canvas.restoreToCount(save)
                        }
                    }
                    // Stroke ON TOP of the tint: the opaque line stays at full
                    // strength (drawn first and then tinted over, it read as a
                    // washed-out half-width line) and sits centred on the
                    // boundary - half on the mesh, half outside it.
                    linePaint.color = DetectionStyle.vibrantFor(classColor)
                    linePaint.alpha = 255
                    for (p in paths) canvas.drawPath(p, linePaint)
                }
            }

            if (showBoxes) {
                boxPaint.color = classColor
                boxPaint.alpha = 190
                canvas.drawRect(screenRect, boxPaint)
                drawCorners(canvas, screenRect, classColor)
                drawLabel(canvas, screenRect, det, classColor, anchorTop = screenRect.top)
            } else {
                // No box: anchor the label to the smoothed top edge of the
                // actual object silhouette so it sits ON the object.
                val anchorTop = item.labelAnchorNorm?.let { offsetY + it * scaledH }
                    ?: screenRect.top
                drawLabel(canvas, screenRect, det, classColor, anchorTop = anchorTop)
            }
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

    private fun drawTintedMask(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
    ) {
        maskPaint.colorFilter = colorFilterFor(classColor)
        maskPaint.alpha = MASK_ALPHA
        canvas.drawBitmap(mask, null, maskRect, maskPaint)
    }

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
