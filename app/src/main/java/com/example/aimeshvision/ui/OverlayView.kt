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
 * ARCHITECTURE NOTE (v4): all per-frame analysis (contour tracing, resampling,
 * temporal smoothing) happens ONCE in [setResults] and is cached in
 * [PreparedItem]s. [onDraw] only draws cached geometry - it never traces,
 * allocates pixel arrays, or touches the smoothing state. The v3 version did
 * the analysis inside onDraw (multiple draws per frame, UI-thread jank) and
 * fed mask-pixel coordinates into screen math un-normalized, which produced
 * a wildly off-screen outline path whose clip erased the mask entirely.
 *
 * Rendering per detection:
 *  1. Smooth outline ON: tinted mask clipped to the traced silhouette (no
 *     mesh outside the line) + vibrant opaque class-color bezier border.
 *     If tracing fails, the plain tinted mask is drawn - the mesh NEVER
 *     disappears because of the outline feature.
 *  2. Smooth outline OFF: plain tinted mask.
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
        private const val OUTLINE_STROKE_W = 5f

        // ── contour pipeline ─────────────────────────────────────────────
        private const val CONTOUR_STEP = 2        // pixel stride while tracing
        private const val RESAMPLE_POINTS = 48    // points around the silhouette
        private const val SIMPLIFY_TOLERANCE = 2.0f

        // ── temporal smoothing (EMA) ─────────────────────────────────────
        // Weight of the PREVIOUS frame in the blend: 0 = raw per-frame values,
        // higher = calmer line/label. 0.65 keeps the outline fluid and kills
        // most per-frame jitter without noticeable lag.
        private const val SMOOTHING = 0.65f
        private const val MAX_TRACK_DISTANCE = 0.35f // normalized; beyond = re-acquire

        // Detections persist this long after they stop being reported, so a
        // single dropped frame never makes the mesh blink out.
        private const val HOLD_MS = 350L
    }

    /** A detection with all per-frame geometry precomputed in [setResults]. */
    private class PreparedItem(
        val det: Detection,
        /** Smoothed contour, normalized 0..1 in mask space (null = none). */
        val contour: List<Pair<Float, Float>>?,
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
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_STROKE_W
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
    private var lastResultsAt = 0L
    private var sourceImageWidth: Int = 640
    private var sourceImageHeight: Int = 640

    /** User-toggleable rendering options (settings sheet). */
    var showBoxes: Boolean = true
    var showSmoothOutline: Boolean = false

    // ── temporal state: smoothed contour + label anchor per class id ─────────
    // The first detection of a class drives the track; extra same-class
    // detections (two cats in frame) render from their raw contour.
    private class Track(
        val points: Array<Pair<Float, Float>>,
        var labelAnchor: Float?,
    )

    private val tracks = mutableMapOf<Int, Track>()

    /**
     * Called once per inference result: precomputes all geometry, updates the
     * temporal tracks, then requests a draw. Cheap draws follow.
     */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight

        // Persistence hold: when a frame reports nothing (a common single-frame
        // dropout), keep drawing the previous geometry for HOLD_MS so the mesh
        // does not visibly blink. Real disappearances expire naturally.
        if (results.isEmpty()) {
            if (System.currentTimeMillis() - lastResultsAt > HOLD_MS) {
                prepared = emptyList()
                invalidate()
            }
            return
        }
        lastResultsAt = System.currentTimeMillis()

        val seenClasses = mutableSetOf<Int>()
        val items = ArrayList<PreparedItem>(results.size)
        for (det in results) {
            val mask = det.maskBitmap
            var contour: List<Pair<Float, Float>>? = null
            var anchor: Float? = null

            if (mask != null) {
                val firstOfClass = seenClasses.add(det.classId)
                val raw = traceContour(mask)
                if (!raw.isNullOrEmpty()) {
                    val resampled = resampleClosed(raw, RESAMPLE_POINTS)
                    // NORMALIZE to 0..1 mask space - screen mapping happens in
                    // onDraw. (v3 bug: mask pixels were used as if normalized.)
                    val norm = resampled.map { p ->
                        (p.first / mask.width) to (p.second / mask.height)
                    }
                    if (firstOfClass) {
                        contour = emaContour(det.classId, norm)
                        anchor = emaAnchor(det.classId, contour)
                    } else {
                        contour = norm
                        anchor = norm.minOfOrNull { it.second }
                    }
                }
            }
            items.add(PreparedItem(det, contour, anchor))
        }
        prepared = items
        invalidate()
    }

    fun clear() {
        prepared = emptyList()
        invalidate()
    }

    /** Forgets all temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        tracks.clear()
        prepared = emptyList()
        invalidate()
    }

    // ── temporal smoothing ────────────────────────────────────────────────────

    /**
     * EMA-blends the raw (normalized) contour with the previous track.
     * Two anti-scribble measures:
     *  1. START-POINT ALIGNMENT: the contour tracer may begin at any pixel;
     *     if the start pixel jumps between frames the point correspondence
     *     twists and the outline scribbles. The raw list is rotated to begin
     *     at the point nearest the previous frame's first point.
     *  2. SPATIAL RELAX: after blending, one neighbor-averaging pass softens
     *     any single-point spikes left by the resampler.
     */
    private fun emaContour(
        classId: Int, rawIn: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> {
        val prev = tracks[classId]
        if (prev == null || prev.points.size != rawIn.size) {
            tracks[classId] = Track(rawIn.toTypedArray(), prev?.labelAnchor)
            return rawIn
        }

        // Rotate raw so index 0 is closest to the previous frame's point 0.
        val n = rawIn.size
        var bestIdx = 0
        var bestD = Float.MAX_VALUE
        val p0 = prev.points[0]
        for (i in 0 until n) {
            val dx = rawIn[i].first - p0.first
            val dy = rawIn[i].second - p0.second
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; bestIdx = i }
        }
        val raw = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) raw.add(rawIn[(bestIdx + i) % n])

        // Temporal blend.
        val out = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val px = prev.points[i].first
            val py = prev.points[i].second
            val nx = raw[i].first
            val ny = raw[i].second
            val dist = sqrt((nx - px) * (nx - px) + (ny - py) * (ny - py))
            out.add(
                if (dist > MAX_TRACK_DISTANCE) nx to ny   // jump = re-acquire
                else px + (nx - px) * (1f - SMOOTHING) to
                    py + (ny - py) * (1f - SMOOTHING)
            )
        }

        // Spatial relax: replace each point with the weighted average of it
        // and its two neighbors (closed loop) - removes residual spikes.
        val relaxed = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val a = out[(i + n - 1) % n]
            val b = out[i]
            val c = out[(i + 1) % n]
            relaxed.add(
                (a.first + 2f * b.first + c.first) / 4f to
                    (a.second + 2f * b.second + c.second) / 4f
            )
        }
        tracks[classId] = Track(relaxed.toTypedArray(), prev.labelAnchor)
        return relaxed
    }

    /** EMA-blends the label anchor (top edge Y, normalized) across frames. */
    private fun emaAnchor(classId: Int, contour: List<Pair<Float, Float>>?): Float? {
        // True object top = min Y over the traced contour (mask space 0..1).
        // The contour now covers the full frame (v6 mask fix), so this is the
        // REAL top edge of the object, not the bounding box.
        val raw = contour?.minOfOrNull { it.second } ?: return null
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
                val path = if (showSmoothOutline && item.contour != null) {
                    buildSmoothPath(item.contour, maskRect)
                } else null

                if (path != null) {
                    // 1. Mask strictly INSIDE the outline: clip to the traced
                    //    silhouette so no mesh pixel leaks past the line.
                    val save = canvas.save()
                    canvas.clipPath(path)
                    drawTintedMask(canvas, mask, maskRect, classColor)
                    canvas.restoreToCount(save)

                    // 2. Vibrant, opaque class-color border on top.
                    outlinePaint.color = DetectionStyle.vibrantFor(classColor)
                    outlinePaint.alpha = 255
                    canvas.drawPath(path, outlinePaint)
                } else {
                    // Outline off (or no traceable silhouette): plain mask -
                    // the mesh is never lost because of the outline feature.
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

    private fun drawTintedMask(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
    ) {
        maskPaint.colorFilter = PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
        maskPaint.alpha = MASK_ALPHA
        canvas.drawBitmap(mask, null, maskRect, maskPaint)
    }

    /** Builds the closed bezier outline from normalized contour points. */
    private fun buildSmoothPath(
        contour: List<Pair<Float, Float>>, maskRect: RectF,
    ): Path {
        val pts = FloatArray(contour.size * 2)
        for (i in contour.indices) {
            pts[i * 2] = maskRect.left + contour[i].first * maskRect.width()
            pts[i * 2 + 1] = maskRect.top + contour[i].second * maskRect.height()
        }
        val path = Path()

        // Midpoints between consecutive points become on-curve anchors; the
        // original points become control points - smooth-curve technique.
        val n = contour.size
        val mids = FloatArray(n * 2)
        for (i in 0 until n) {
            val j = (i + 1) % n
            mids[i * 2] = (pts[i * 2] + pts[j * 2]) / 2f
            mids[i * 2 + 1] = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
        }
        path.reset()
        path.moveTo(mids[0], mids[1])
        for (i in 0 until n) {
            val j = (i + 1) % n
            path.quadTo(
                pts[j * 2], pts[j * 2 + 1],      // control = real contour point
                mids[j * 2], mids[j * 2 + 1],    // anchor   = midpoint
            )
        }
        path.close()
        return path
    }

    /**
     * Resamples a closed contour to exactly [n] evenly-spaced points by
     * arc length, which stabilizes the point correspondence between frames
     * (required for meaningful EMA blending).
     */
    private fun resampleClosed(
        contour: List<Pair<Int, Int>>, n: Int,
    ): List<Pair<Float, Float>> {
        if (contour.size < 3) return contour.map { it.first.toFloat() to it.second.toFloat() }

        var total = 0f
        val dist = FloatArray(contour.size)
        for (i in contour.indices) {
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            val d = sqrt(
                (b.first - a.first).toFloat() * (b.first - a.first) +
                    (b.second - a.second).toFloat() * (b.second - a.second)
            )
            dist[i] = d
            total += d
        }
        if (total <= 0f) return contour.map { it.first.toFloat() to it.second.toFloat() }

        val step = total / n
        val out = ArrayList<Pair<Float, Float>>(n)
        var i = 0
        var traveled = 0f
        for (k in 0 until n) {
            val target = k * step
            while (i < contour.size - 1 && traveled + dist[i] < target) {
                traveled += dist[i]
                i++
            }
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            val seg = dist[i].coerceAtLeast(1e-6f)
            val t = ((target - traveled) / seg).coerceIn(0f, 1f)
            out.add(
                a.first + (b.first - a.first) * t to
                    a.second + (b.second - a.second) * t
            )
        }
        return out
    }

    /**
     * Moore-neighborhood border tracing on the mask alpha, downsampled by
     * [CONTOUR_STEP] and lightly simplified. Returns null when the mask is
     * empty or tracing fails - callers just skip the outline.
     */
    private fun traceContour(mask: Bitmap): List<Pair<Int, Int>>? {
        val w = mask.width
        val h = mask.height
        if (w < 4 || h < 4) return null

        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        fun solid(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && (pixels[y * w + x] ushr 24) > 128

        var sx = -1; var sy = -1
        outer@ for (y in 0 until h step CONTOUR_STEP) {
            for (x in 0 until w step CONTOUR_STEP) {
                if (solid(x, y)) { sx = x; sy = y; break@outer }
            }
        }
        if (sx < 0) return null

        val dirs = arrayOf(
            1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
        )
        val contour = ArrayList<Pair<Int, Int>>(256)
        var cx = sx; var cy = sy
        var dir = 0
        var steps = 0
        val maxSteps = w * h
        do {
            contour.add(cx to cy)
            var found = false
            for (i in 0 until 8) {
                val nd = (dir + 6 + i) % 8
                val nx = cx + dirs[nd].first
                val ny = cy + dirs[nd].second
                if (solid(nx, ny)) {
                    cx = nx; cy = ny; dir = nd; found = true
                    break
                }
            }
            if (!found) break
            steps++
        } while ((cx != sx || cy != sy) && steps < maxSteps)

        if (contour.size < 8) return null

        // Distance-based simplification for a cleaner resampler input.
        val simplified = ArrayList<Pair<Int, Int>>(contour.size / 2 + 4)
        var lastX = -1; var lastY = -1
        for (p in contour) {
            val dx = p.first - lastX
            val dy = p.second - lastY
            if (lastX < 0 ||
                dx * dx + dy * dy >= SIMPLIFY_TOLERANCE * SIMPLIFY_TOLERANCE
            ) {
                simplified.add(p); lastX = p.first; lastY = p.second
            }
        }
        return simplified.ifEmpty { contour }
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
        val text = "${det.label.uppercase()}  $confPercent%"

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
