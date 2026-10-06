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
 * Rendering pipeline per detection:
 *  1. The segmentation mask (tinted with the class color) — when the smooth
 *     outline is on, the mask is CLIPPED to the traced outline so no mask
 *     pixels leak outside the border line.
 *  2. Optional bounding box with corner brackets (user-toggleable).
 *  3. Optional smooth outline: the mask silhouette is contour-traced,
 *     temporally smoothed across frames (EMA), resampled, and converted to
 *     quadratic-bezier curves → a fluid line that hugs the object. Stroke
 *     color is the class color, made vibrant + opaque (never white).
 *  4. Label chip — anchored to the top edge of the object (mask top when
 *     boxes are hidden), its position temporally smoothed so it glides
 *     instead of jittering.
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

        // ── contour tracing ─────────────────────────────────────────────
        private const val CONTOUR_STEP = 2        // pixel stride while tracing
        private const val RESAMPLE_POINTS = 48    // control points around the silhouette
        private const val SIMPLIFY_TOLERANCE = 2.0f

        // ── temporal smoothing (EMA) ────────────────────────────────────
        // Higher SMOOTHING = calmer line/label; 0 = raw per-frame values.
        private const val SMOOTHING = 0.45f
        private const val MAX_TRACK_DISTANCE = 0.35f // normalized; beyond = re-acquire
    }

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
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var detections: List<Detection> = emptyList()
    private var sourceImageWidth: Int = 640
    private var sourceImageHeight: Int = 640

    /** User-toggleable rendering options (settings sheet). */
    var showBoxes: Boolean = true
    var showSmoothOutline: Boolean = false

    // ── temporal state: one smoothed contour + label anchor per class id ─────
    private data class Smoothed(
        val points: Array<Pair<Float, Float>>,   // normalized mask-space coords (outline)
        val labelAnchor: Float?,                 // normalized top-edge Y (label)
    )

    private val tracks = mutableMapOf<Int, Smoothed>()

    /** Updates detections and triggers a redraw. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        detections = results
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    /** Forgets all temporal smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        tracks.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        // fitCenter mapping between the source image and this view.
        val scaleX = width.toFloat() / sourceImageWidth
        val scaleY = height.toFloat() / sourceImageHeight
        val scale = minOf(scaleX, scaleY)
        val scaledW = sourceImageWidth * scale
        val scaledH = sourceImageHeight * scale
        val offsetX = (width - scaledW) / 2f
        val offsetY = (height - scaledH) / 2f

        for (det in detections) {
            val classColor = DetectionStyle.colorFor(det.classId)

            val left = det.boundingBox.left * scaledW + offsetX
            val top = det.boundingBox.top * scaledH + offsetY
            val right = det.boundingBox.right * scaledW + offsetX
            val bottom = det.boundingBox.bottom * scaledH + offsetY
            val screenRect = RectF(left, top, right, bottom)

            val mask = det.maskBitmap
            if (mask != null) {
                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

                if (showSmoothOutline) {
                    drawSmoothMesh(canvas, det, mask, maskRect, classColor)
                } else {
                    maskPaint.colorFilter =
                        PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
                    maskPaint.alpha = MASK_ALPHA
                    canvas.drawBitmap(mask, null, maskRect, maskPaint)
                }
            }

            if (showBoxes) {
                boxPaint.color = classColor
                boxPaint.alpha = 190
                canvas.drawRect(screenRect, boxPaint)
                drawCorners(canvas, screenRect, classColor)
                drawLabel(canvas, screenRect, det, classColor, anchorTop = screenRect.top)
            } else {
                // No box: the label anchors to the smoothed top edge of the
                // actual object silhouette so it sits ON the object.
                val anchorTop = smoothedMaskTopY(det, offsetX, offsetY, scaledW, scaledH)
                    ?: screenRect.top
                drawLabel(canvas, screenRect, det, classColor, anchorTop = anchorTop)
            }
        }
    }

    // ── smooth mesh: trace → smooth → clip mask → vibrant outline ────────────

    private fun drawSmoothMesh(
        canvas: Canvas, det: Detection, mask: Bitmap, maskRect: RectF, classColor: Int,
    ) {
        val raw = traceContour(mask)
        if (raw.isNullOrEmpty()) {
            // No traceable silhouette - fall back to the plain tinted mask.
            maskPaint.colorFilter = PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
            maskPaint.alpha = MASK_ALPHA
            canvas.drawBitmap(mask, null, maskRect, maskPaint)
            return
        }

        // Temporal EMA against the previous frame's contour for this class.
        val smoothed = smoothContour(det.classId, raw)

        // Build the bezier path in screen space.
        val path = Path()
        val pts = FloatArray(smoothed.size * 2)
        for (i in smoothed.indices) {
            pts[i * 2] = maskRect.left + smoothed[i].first * maskRect.width()
            pts[i * 2 + 1] = maskRect.top + smoothed[i].second * maskRect.height()
        }
        buildClosedBezier(path, pts)

        // 1. Mask strictly INSIDE the outline: tint the mask, then clip it to
        //    the traced silhouette so no mesh pixel leaks past the line.
        val save = canvas.save()
        canvas.clipPath(path)
        maskPaint.colorFilter = PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
        maskPaint.alpha = MASK_ALPHA
        canvas.drawBitmap(mask, null, maskRect, maskPaint)
        canvas.restoreToCount(save)

        // 2. Vibrant, opaque class-color border on top.
        outlinePaint.color = DetectionStyle.vibrantFor(classColor)
        outlinePaint.alpha = 255
        canvas.drawPath(path, outlinePaint)
    }

    /** EMA-blends the raw contour with the previous frame's smoothed contour. */
    private fun smoothContour(classId: Int, raw: List<Pair<Int, Int>>): List<Pair<Float, Float>> {
        val resampled = resampleClosed(raw, RESAMPLE_POINTS)
        val prev = tracks[classId]?.points

        val out = ArrayList<Pair<Float, Float>>(resampled.size)
        if (prev == null || prev.size != resampled.size) {
            out.addAll(resampled.map { it.first to it.second })
        } else {
            for (i in resampled.indices) {
                val px = prev[i].first
                val py = prev[i].second
                val nx = resampled[i].first
                val ny = resampled[i].second
                val dist = sqrt((nx - px) * (nx - px) + (ny - py) * (ny - py))
                out.add(
                    if (dist > MAX_TRACK_DISTANCE) nx to ny   // jump = re-acquire
                    else px + (nx - px) * (1f - SMOOTHING) to
                        py + (ny - py) * (1f - SMOOTHING)
                )
            }
        }
        tracks[classId] = Smoothed(out.toTypedArray(), tracks[classId]?.labelAnchor)
        return out
    }

    /** EMA-blends the label anchor Y so the chip glides instead of jittering. */
    private fun smoothedMaskTopY(
        det: Detection, offsetX: Float, offsetY: Float, scaledW: Float, scaledH: Float,
    ): Float? {
        val mask = det.maskBitmap ?: return null
        var topRow = -1
        val w = mask.width
        val px = IntArray(w)
        outer@ for (y in 0 until mask.height) {
            mask.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (px[x] != 0) { topRow = y; break@outer }
            }
        }
        if (topRow < 0) return null

        // Raw top edge in normalized mask space.
        val rawNorm = topRow.toFloat() / mask.height
        val prev = tracks[det.classId]?.labelAnchor
        val smoothed = if (prev == null || abs(rawNorm - prev) > MAX_TRACK_DISTANCE) {
            rawNorm
        } else {
            prev + (rawNorm - prev) * (1f - SMOOTHING)
        }
        tracks[det.classId] = Smoothed(
            tracks[det.classId]?.points ?: emptyArray(),
            smoothed,
        )
        return offsetY + smoothed * scaledH
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

        // Total perimeter.
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
     * Converts a closed polyline of screen-space points into a smooth path of
     * quadratic bezier segments (midpoint technique): corners become gentle
     * curves, sharp features that the object actually has are preserved
     * because the control points sit on the real contour.
     */
    private fun buildClosedBezier(path: Path, pts: FloatArray) {
        val n = pts.size / 2
        if (n < 3) return

        // Midpoints between consecutive points become on-curve anchors; the
        // original points become control points - classic smooth-curve trick.
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

        // Distance-based simplification for a cleaner input to the resampler.
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
