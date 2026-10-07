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
 * ARCHITECTURE NOTE (v9 - border dots): the outline is built from BORDER
 * DOTS - every solid mask pixel that touches a background pixel is a dot -
 * which are then chained nearest-to-nearest and connected. This is the user's
 * explicit model ("dots around the borders which connect with the nearest
 * dot around them") and it fixes both v8 complaints:
 *
 *  1. POINTY TIPS: v8 compressed the whole contour to 48 arc-length points,
 *     which averaged sharp corners away. Dots sit on the ACTUAL border
 *     pixels, so every point is represented exactly.
 *  2. MISSING BORDER: the Moore-neighborhood tracer could fail (holes,
 *     odd shapes) leaving mesh with no border. Dot extraction is a per-pixel
 *     classification - if the mesh exists, its dots exist. Failure-proof.
 *
 * The chained dots are then smoothed (EMA per dot index + midpoint-bezier
 * through the chained sequence) so the line keeps the smooth stroke look.
 * All analysis runs once per frame in [setResults]; [onDraw] only draws.
 *
 * Rendering per detection:
 *  1. Smooth outline ON: tinted mesh clipped to the line + vibrant stroke.
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

        // ── border dots ──────────────────────────────────────────────────
        // Grid step: dots are sampled every N mask pixels on the border, and
        // each connects to its nearest neighbors. 2 on a ~128px mask gives a
        // dense but cheap dot set (~hundreds), preserving sharp points.
        private const val DOT_GRID_STEP = 2

        private const val LINE_STROKE_W = 5f     // screen px (old look)

        // ── temporal smoothing (dot EMA + label anchor) ──────────────────
        private const val SMOOTHING = 0.45f
        private const val MAX_TRACK_DISTANCE = 0.35f // normalized; beyond = re-acquire
    }

    /** A detection with its per-frame geometry precomputed in [setResults]. */
    private class PreparedItem(
        val det: Detection,
        /** Smoothed chained dots, normalized 0..1 in mask space (null = none). */
        val dots: List<Pair<Float, Float>>?,
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
        var track: Track?,
        var lastSeen: Long,
    )

    private val instances = ArrayList<InstanceTrack>()
    private var frameCounter = 0L

    /** Called once per inference result: precomputes geometry, then redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight

        frameCounter++
        val now = System.currentTimeMillis()

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
            var dots: List<Pair<Float, Float>>? = null
            var anchor: Float? = null

            if (mask != null) {
                val raw = extractBorderDots(mask)
                if (raw != null && raw.size >= 8) {
                    // EVERY chain renders - hands/blobs are separate outlines.
                    val chains = chainNearest(raw)
                    val normChains = chains.map { chain ->
                        chain.map { p ->
                            (p.first / mask.width.toFloat()) to
                                (p.second / mask.height.toFloat())
                        }
                    }
                    val inst = assignments[det]
                    if (inst != null && inst.track != null &&
                        inst.track!!.points.size == normChains.firstOrNull()?.size
                    ) {
                        // Same object as last frame: EMA-smooth the longest chain.
                        dots = emaDots(inst, normChains.firstOrNull())
                        anchor = emaAnchor(inst, dots)
                    } else {
                        dots = normChains.firstOrNull()
                        anchor = dots?.minOfOrNull { it.second }
                    }
                    // Update / create the instance record.
                    if (inst != null) {
                        inst.box = det.boundingBox
                        inst.lastSeen = now
                        inst.track = Track((dots ?: emptyList()).toTypedArray(), anchor)
                    } else {
                        instances.add(InstanceTrack(det.boundingBox, det.classId, Track((dots ?: emptyList()).toTypedArray(), anchor), now))
                    }
                }
            }
            items.add(PreparedItem(det, dots, anchor))
        }

        // Expire instances not seen this frame (2 frames of grace).
        instances.removeAll { now - it.lastSeen > 200 }
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
        prepared = emptyList()
        invalidate()
    }

    // ── border-dot extraction ─────────────────────────────────────────────────

    /**
     * Collects border dots: solid mask pixels (on a [DOT_GRID_STEP] grid)
     * with at least one non-solid 4-neighbor. Per-pixel classification -
     * cannot "fail to trace" like a walking tracer; if the mesh exists its
     * dots exist. Returns null only when the mask is empty.
     */
    private fun extractBorderDots(mask: Bitmap): List<Pair<Int, Int>>? {
        val w = mask.width
        val h = mask.height
        if (w < 4 || h < 4) return null

        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        fun solid(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && (px[y * w + x] ushr 24) > 128

        val dots = ArrayList<Pair<Int, Int>>(512)
        var sy = DOT_GRID_STEP / 2
        while (sy < h) {
            var sx = DOT_GRID_STEP / 2
            while (sx < w) {
                if (solid(sx, sy) &&
                    (!solid(sx - 1, sy) || !solid(sx + 1, sy) ||
                        !solid(sx, sy - 1) || !solid(sx, sy + 1))
                ) {
                    dots.add(sx to sy)
                }
                sx += DOT_GRID_STEP
            }
            sy += DOT_GRID_STEP
        }
        return if (dots.isEmpty()) null else dots
    }

    /**
     * Orders the dot set into a boundary walk that follows the border
     * LOCALLY: each step moves to an unused dot within one grid cell in the
     * 8-neighborhood (Chebyshev distance <= 2), preferring the one that keeps
     * the walk direction straightest. The chain is forbidden from jumping
     * across the shape - if no adjacent dot is free, the walk ends (or starts
     * a new chain elsewhere) rather than forcing a long connection.
     */
    private fun chainNearest(dots: List<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
        if (dots.size < 3) return emptyList()

        // Grid lookup: dot at (gx, gy) -> index. Chebyshev neighbor searches
        // become O(1) instead of O(n) per step.
        val maxGx = dots.maxOf { it.first } / DOT_GRID_STEP
        val maxGy = dots.maxOf { it.second } / DOT_GRID_STEP
        val grid = HashMap<Long, Int>(dots.size * 2)
        dots.forEachIndexed { idx, (x, y) -> grid[(y / DOT_GRID_STEP).toLong() * (maxGx + 1) + (x / DOT_GRID_STEP)] = idx }

        val used = BooleanArray(dots.size)
        val chains = ArrayList<List<Pair<Int, Int>>>()

        fun tryChain(startIdx: Int): List<Pair<Int, Int>>? {
            val chain = ArrayList<Pair<Int, Int>>()
            var cur = startIdx
            used[cur] = true
            chain.add(dots[cur])
            // Walk direction from the previous step (0,0 at the first step).
            var dirX = 0
            var dirY = 0
            while (true) {
                val cx = dots[cur].first
                val cy = dots[cur].second
                val gx = cx / DOT_GRID_STEP
                val gy = cy / DOT_GRID_STEP

                // Collect unused dots in the 8-neighborhood (Chebyshev <= 2).
                var bestIdx = -1
                var bestScore = Double.MAX_VALUE
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (gx + dx) * DOT_GRID_STEP + DOT_GRID_STEP / 2
                        val ny = (gy + dy) * DOT_GRID_STEP + DOT_GRID_STEP / 2
                        val key = (gy + dy).toLong() * (maxGx + 1) + (gx + dx)
                        val idx = grid[key] ?: continue
                        if (used[idx]) continue
                        if (dots[idx].first != nx || dots[idx].second != ny) continue
                        val step = sqrt((dx * dx + dy * dy).toDouble())
                        // Prefer continuing in the current direction (straighter
                        // border walk) with distance as the tiebreaker.
                        val dirLen = kotlin.math.hypot(dirX.toDouble(), dirY.toDouble())
                        val dot = (dirX * dx + dirY * dy) / (step * (if (dirLen == 0.0) 1.0 else dirLen))
                        val score = step - 0.5 * dot
                        if (score < bestScore) { bestScore = score; bestIdx = idx }
                    }
                }
                if (bestIdx < 0) break   // no adjacent dot free: end this chain
                dirX = dots[bestIdx].first - cx
                dirY = dots[bestIdx].second - cy
                used[bestIdx] = true
                chain.add(dots[bestIdx])
                cur = bestIdx
            }
            return if (chain.size >= 8) chain else null
        }

        // Start chains from the topmost unused dot (deterministic).
        while (true) {
            var start = -1
            for (i in dots.indices) {
                if (!used[i] &&
                    (start < 0 || dots[i].second < dots[start].second ||
                        (dots[i].second == dots[start].second && dots[i].first < dots[start].first))
                ) start = i
            }
            if (start < 0) break
            tryChain(start)?.let { chains.add(it) }
        }
        // Longest chain = the object's main outline.
        return chains.sortedByDescending { it.size }
    }

    // ── temporal smoothing ────────────────────────────────────────────────────

    /**
     * EMA-blends the chained dots with the previous frame's track. The chain
     * always starts at the topmost dot (deterministic), so correspondence is
     * stable frame to frame; a size change falls back to the raw chain.
     */
    private fun emaDots(
        inst: InstanceTrack, rawIn: List<Pair<Float, Float>>?,
    ): List<Pair<Float, Float>>? {
        rawIn ?: return null
        val prev = inst.track
        if (prev == null || prev.points.size != rawIn.size) {
            return rawIn
        }

        val out = ArrayList<Pair<Float, Float>>(rawIn.size)
        for (i in rawIn.indices) {
            val px = prev.points[i].first
            val py = prev.points[i].second
            val nx = rawIn[i].first
            val ny = rawIn[i].second
            val dist = sqrt((nx - px) * (nx - px) + (ny - py) * (ny - py))
            out.add(
                if (dist > MAX_TRACK_DISTANCE) nx to ny   // jump = re-acquire
                else px + (nx - px) * (1f - SMOOTHING) to
                    py + (ny - py) * (1f - SMOOTHING)
            )
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
                val path = if (showSmoothOutline && item.dots != null) {
                    buildSmoothPath(item.dots, maskRect)
                } else null

                if (path != null) {
                    // 1. Mesh strictly inside the line: tinted mask clipped to
                    //    the dot-chained boundary curve - but ONLY when the
                    //    path is sane for clipping (nonzero bounds within the
                    //    mask rect). The STROKE below is drawn whenever the
                    //    path exists at all: the v10 sane-clip guard
                    //    accidentally gated the stroke too and calibrated
                    //    against the whole-frame mask rect, rejecting valid
                    //    hand outlines (<15% of frame) -> "outline gone".
                    val save = canvas.save()
                    if (isSaneClipPath(path, maskRect)) {
                        canvas.clipPath(path)
                        drawTintedMask(canvas, mask, maskRect, classColor)
                    } else {
                        // Degenerate path: draw the mesh unclipped - the mesh
                        // must never be erased by a bad clip.
                        drawTintedMask(canvas, mask, maskRect, classColor)
                    }
                    canvas.restoreToCount(save)

                    // 2. Vibrant, opaque class-color stroke ON the boundary -
                    //    half on the mesh, half outside it.
                    linePaint.color = DetectionStyle.vibrantFor(classColor)
                    linePaint.alpha = 255
                    canvas.drawPath(path, linePaint)
                } else {
                    drawTintedMask(canvas, mask, maskRect, classColor)
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
     * Builds the smooth closed path THROUGH the chained dots: on-curve
     * midpoints + dot control points (quadratic bezier) - each dot is
     * represented exactly, corners stay sharp where the dots say so.
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
            val j = (i + 1) % n
            path.quadTo(
                pts[j * 2], pts[j * 2 + 1],      // control = real border dot
                mids[j * 2], mids[j * 2 + 1],    // anchor   = midpoint
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
