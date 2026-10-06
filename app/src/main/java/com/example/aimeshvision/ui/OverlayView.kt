package com.example.aimeshvision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.aimeshvision.inference.Detection

/**
 * Full-screen canvas that maps normalized detections onto screen space.
 *
 * Per detection it can draw:
 *  - the segmentation mask (tinted, when the model provides one)
 *  - an optional bounding box with corner brackets (user-toggleable:
 *    [showBoxes]; off = clean mesh-only look)
 *  - a label chip that, with boxes hidden, floats ON TOP of the object
 *    (anchored to the mask top edge) instead of at an invisible box corner
 *  - an optional SMOOTH OUTLINE: the raw prototype mask is contour-traced,
 *    simplified, and stroked as one clean highlight line hugging the object
 *    border - toggleable via [showSmoothOutline]
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private val CLASS_COLORS = intArrayOf(
            Color.parseColor("#BCF362"), // lime (app accent family)
            Color.parseColor("#00E5FF"), // cyan
            Color.parseColor("#FF6EC7"), // pink
            Color.parseColor("#FFD166"), // amber
            Color.parseColor("#FF6B4A"), // coral
            Color.parseColor("#5EA8FF"), // blue
            Color.parseColor("#C084FC"), // violet
            Color.parseColor("#4ADE80"), // green
        )

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

        // Contour tracing walks the mask bitmap pixels, so the outline is
        // derived at bitmap resolution and scaled up with the mask rect.
        private const val CONTOUR_STEP = 2   // pixel stride while tracing
        private const val SIMPLIFY_TOLERANCE = 2.5f // px tolerance for path simplify
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

    private var detections: List<Detection> = emptyList()
    private var sourceImageWidth: Int = 640
    private var sourceImageHeight: Int = 640

    /** User-toggleable rendering options (settings sheet). */
    var showBoxes: Boolean = true
    var showSmoothOutline: Boolean = false

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
            val classColor = CLASS_COLORS[det.classId % CLASS_COLORS.size]

            val left = det.boundingBox.left * scaledW + offsetX
            val top = det.boundingBox.top * scaledH + offsetY
            val right = det.boundingBox.right * scaledW + offsetX
            val bottom = det.boundingBox.bottom * scaledH + offsetY
            val screenRect = RectF(left, top, right, bottom)

            // segmentation mask first (below everything else)
            det.maskBitmap?.let { mask ->
                val maskRect = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                maskPaint.colorFilter = PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
                maskPaint.alpha = MASK_ALPHA
                canvas.drawBitmap(mask, null, maskRect, maskPaint)

                if (showSmoothOutline) {
                    drawSmoothOutline(canvas, mask, maskRect, classColor)
                }
            }

            // Bounding box + corners only when the user wants them. The label
            // is ALWAYS drawn, but its anchor changes with the box mode.
            if (showBoxes) {
                boxPaint.color = classColor
                boxPaint.alpha = 190
                canvas.drawRect(screenRect, boxPaint)
                drawCorners(canvas, screenRect, classColor)
                drawLabel(canvas, screenRect, det, classColor, anchorTop = screenRect.top)
            } else {
                // No box: anchor the label to the top edge of the actual mask
                // (the real object boundary) so it sits ON the object.
                val anchorTop = maskTopScreenY(det, offsetX, offsetY, scaledW, scaledH)
                    ?: screenRect.top
                drawLabel(canvas, screenRect, det, classColor, anchorTop = anchorTop)
            }
        }
    }

    /**
     * Screen-space Y of the topmost opaque mask row (the true top edge of the
     * object). Falls back to the box top when no mask exists.
     */
    private fun maskTopScreenY(
        det: Detection, offsetX: Float, offsetY: Float, scaledW: Float, scaledH: Float,
    ): Float? {
        val mask = det.maskBitmap ?: return null
        var topRow = -1
        val w = mask.width
        val px = IntArray(w)
        // Scan a few rows down at most - the top edge is near the start.
        outer@ for (y in 0 until mask.height) {
            mask.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (px[x] != 0) { topRow = y; break@outer }
            }
        }
        if (topRow < 0) return null
        return offsetY + (topRow.toFloat() / mask.height) * scaledH
    }

    /**
     * Traces the silhouette of the mask bitmap into a smooth closed path and
     * strokes it as a highlight border, mapped into the mask's screen rect.
     */
    private fun drawSmoothOutline(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
    ) {
        val contour = traceContour(mask) ?: return
        val path = android.graphics.Path()
        var first = true
        for (p in contour) {
            val sx = maskRect.left + p.first * maskRect.width() / mask.width
            val sy = maskRect.top + p.second * maskRect.height() / mask.height
            if (first) { path.moveTo(sx, sy); first = false } else path.lineTo(sx, sy)
        }
        path.close()

        // Simple path smoothing: rounded joins via corner-radius effect is
        // handled by the ROUND stroke join; extra polish via a second, wider
        // translucent pass gives the "highlight" glow.
        outlinePaint.color = classColor
        outlinePaint.alpha = 60
        outlinePaint.strokeWidth = OUTLINE_STROKE_W * 2.4f
        canvas.drawPath(path, outlinePaint)

        outlinePaint.color = Color.WHITE
        outlinePaint.alpha = 220
        outlinePaint.strokeWidth = OUTLINE_STROKE_W * 0.8f
        canvas.drawPath(path, outlinePaint)
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

        // find start: first solid pixel scanning top-left to bottom-right
        var sx = -1; var sy = -1
        outer@ for (y in 0 until h step CONTOUR_STEP) {
            for (x in 0 until w step CONTOUR_STEP) {
                if (solid(x, y) || solid(x + 1, y)) { sx = x; sy = y; break@outer }
            }
        }
        if (sx < 0) return null

        // Moore-neighborhood trace (8-connectivity), bounded by mask size.
        val dirs = arrayOf(
            1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
        )
        val contour = ArrayList<Pair<Int, Int>>(256)
        var cx = sx; var cy = sy
        var dir = 0
        var steps = 0
        val maxSteps = w * h // hard bound; any sane silhouette closes earlier
        do {
            contour.add(cx to cy)
            var found = false
            // search neighbors starting from the backtrack direction
            for (i in 0 until 8) {
                val nd = (dir + 6 + i) % 8
                val nx = cx + dirs[nd].first
                val ny = cy + dirs[nd].second
                if (solid(nx, ny)) {
                    cx = nx; cy = ny; dir = nd; found = true
                    break
                }
            }
            if (!found) break // isolated pixel
            steps++
        } while ((cx != sx || cy != sy) && steps < maxSteps)

        if (contour.size < 8) return null

        // Downsample + distance-based simplification for a clean line.
        val simplified = ArrayList<Pair<Int, Int>>(contour.size / 2 + 4)
        var last: Pair<Int, Int>? = null
        for (p in contour) {
            if (last == null ||
                (p.first - last!!.first) * (p.first - last!!.first) +
                (p.second - last!!.second) * (p.second - last!!.second) >=
                SIMPLIFY_TOLERANCE * SIMPLIFY_TOLERANCE
            ) {
                simplified.add(p); last = p
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
     * Draws the label chip at [anchorTop] (the top of the object when boxes
     * are hidden, the box top otherwise).
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
