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
 * Draws, per detection:
 *  - the segmentation mask (tinted, when the model provides one)
 *  - a real bounding box with corner brackets (was prepared-but-unused in v1)
 *  - a rounded label chip with class name + confidence
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
        private const val LABEL_TEXT_SIZE = 34f
        private const val LABEL_PADDING = 12f
        private const val LABEL_CORNER_R = 14f
        private const val MASK_ALPHA = 110
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
                maskPaint.colorFilter = PorterDuffColorFilter(classColor, PorterDuff.Mode.SRC_IN)
                maskPaint.alpha = MASK_ALPHA
                canvas.drawBitmap(
                    mask, null,
                    RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH),
                    maskPaint,
                )
            }

            // real bounding box + corner brackets (v1 defined these but never drew them)
            boxPaint.color = classColor
            boxPaint.alpha = 190
            canvas.drawRect(screenRect, boxPaint)
            drawCorners(canvas, screenRect, classColor)

            drawLabel(canvas, screenRect, det, classColor)
        }
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

    private fun drawLabel(canvas: Canvas, rect: RectF, det: Detection, classColor: Int) {
        val confPercent = (det.confidence * 100).toInt()
        val text = "${det.label.uppercase()}  $confPercent%"

        val textWidth = labelTextPaint.measureText(text)
        val chipW = textWidth + LABEL_PADDING * 2f
        val chipH = labelTextPaint.textSize + LABEL_PADDING * 2f

        val chipTop = if (rect.top - chipH >= 0f) rect.top - chipH else rect.bottom
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
