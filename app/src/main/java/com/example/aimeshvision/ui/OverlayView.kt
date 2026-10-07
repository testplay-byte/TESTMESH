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

    // ── temporal state (per class id; first detection of a class drives it) ──
    private class Track(
        val points: Array<Pair<Float, Float>>,
        var labelAnchor: Float?,
    )

    private val tracks = mutableMapOf<Int, Track>()

    /** Called once per inference result: precomputes geometry, then redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight

        val seenClasses = mutableSetOf<Int>()
        val items = ArrayList<PreparedItem>(results.size)
        for (det in results) {
            val mask = det.maskBitmap
            var dots: List<Pair<Float, Float>>? = null
            var anchor: Float? = null

            if (mask != null) {
                val firstOfClass = seenClasses.add(det.classId)
                val raw = extractBorderDots(mask)
                if (raw != null && raw.size >= 8) {
                    val chained = chainNearest(raw)
                    // Normalize to 0..1 mask space BEFORE any screen mapping.
                    val norm = chained.map { p ->
                        (p.first / mask.width) to (p.second / mask.height)
                    }
                    if (firstOfClass) {
                        dots = emaDots(det.classId, norm)
                        anchor = emaAnchor(det.classId, dots)
                    } else {
                        dots = norm
                        anchor = norm.minOfOrNull { it.second }
                    }
                }
            }
            items.add(PreparedItem(det, dots, anchor))
        }
        prepared = items
        invalidate()
    }

    fun clear() {
        prepared = emptyList()
        invalidate()
    }

    /** Forgets temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        tracks.clear()
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
     * Orders the unordered dot set into a boundary walk: each dot connects to
     * its nearest not-yet-used dot ("dots connect with the nearest dot around
     * them"). Greedy nearest-neighbor on a planar closed outline follows the
     * boundary in order; the result is closed by connecting back to the start.
     */
    private fun chainNearest(dots: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (dots.size < 3) return dots

        val n = dots.size
        val used = BooleanArray(n)
        val order = ArrayList<Pair<Int, Int>>(n)

        // Start at the topmost dot (deterministic, near the label anchor).
        var cur = 0
        for (i in 1 until n) {
            if (dots[i].second < dots[cur].second ||
                (dots[i].second == dots[cur].second && dots[i].first < dots[cur].first)
            ) cur = i
        }
        used[cur] = true
        order.add(dots[cur])

        for (step in 1 until n) {
            val cx = dots[cur].first
            val cy = dots[cur].second
            var best = -1
            var bestD = Int.MAX_VALUE
            for (i in 0 until n) {
                if (used[i]) continue
                val dx = dots[i].first - cx
                val dy = dots[i].second - cy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
            }
            if (best < 0) break
            used[best] = true
            order.add(dots[best])
            cur = best
        }
        return order
    }

    // ── temporal smoothing ────────────────────────────────────────────────────

    /**
     * EMA-blends the chained dots with the previous frame's track. The chain
     * always starts at the topmost dot (deterministic), so correspondence is
     * stable frame to frame; a size change falls back to the raw chain.
     */
    private fun emaDots(
        classId: Int, rawIn: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        val prev = tracks[classId]
        if (prev == null || prev.points.size != rawIn.size) {
            tracks[classId] = Track(rawIn.toTypedArray(), prev?.labelAnchor)
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
        tracks[classId] = Track(out.toTypedArray(), prev.labelAnchor)
        return out
    }

    /** EMA-blends the label anchor (top edge Y, normalized) across frames. */
    private fun emaAnchor(classId: Int, dots: List<Pair<Float, Float>>?): Float? {
        val raw = dots?.minOfOrNull { it.second } ?: return null
        val prev = tracks[classId]?.labelAnchor
        val smoothed = if (prev == null || abs(raw - prev) > MAX_TRACK_DISTANCE) {
            raw
        } else {
            prev + (raw - prev) * (1f - SMOOTHING)
        }
        tracks[classId]?.labelAnchor = smoothed
        return smoothed
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
                    //    the dot-chained boundary curve.
                    val save = canvas.save()
                    canvas.clipPath(path)
                    drawTintedMask(canvas, mask, maskRect, classColor)
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
