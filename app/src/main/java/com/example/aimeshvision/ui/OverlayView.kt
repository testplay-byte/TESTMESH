package com.example.aimeshvision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.aimeshvision.inference.Detection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen canvas that maps normalized detections onto screen space.
 *
 * ARCHITECTURE NOTE (v5 - ring outline): earlier versions traced the mask
 * contour into a smoothed bezier path and CLIPPED the mask to it. That design
 * could never be both smooth AND accurate: wherever the fitted line lagged
 * behind the real mask (thin fingertips, fast motion), the clip cut the mesh
 * off. v5 removes contour tracing and clipping entirely.
 *
 * The outline is now built FROM THE MASK'S OWN PIXELS: the silhouette is
 * dilated by a few pixels across 16 directions, then the original mask is
 * subtracted - leaving a ring that hugs the mask exactly, everywhere,
 * by construction. Because nothing is ever clipped, the mask can never be
 * cut off: fingertips, edges, corners are always inside both mesh and line.
 *
 * Rendering per detection:
 *  1. Smooth outline ON: tinted mask (full, uncut) + vibrant ring on top.
 *  2. Smooth outline OFF: tinted mask only.
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

        // ── ring outline ─────────────────────────────────────────────────
        // The line is the ring between two dilations of the mask:
        //   line = dilate(mask, RING_OUT_PX) - dilate(mask, RING_IN_PX)
        // Both dilations are stamp-based (no erosion pitfalls), the band hugs
        // the mask's true boundary everywhere, and its width in mask pixels is
        // RING_OUT_PX - RING_IN_PX ≈ 0.75px ≈ 3 screen px at 512 input.
        // Sub-pixel stamp offsets + bilinear filtering feather the edges.
        private const val RING_OUT_PX = 1.05f
        private const val RING_IN_PX = 0.3f
        private const val RING_DIRECTIONS = 24

        // Temporal blend: last frame's ring is unioned in at this alpha, which
        // softens single-frame edge flicker (the smoothness of the old EMA
        // line, with zero curve fitting). Regions the mask truly drops decay
        // geometrically and vanish in a few frames - no ghosting.
        private const val RING_TEMPORAL_ALPHA = 120

        // ── temporal smoothing (label anchor EMA) ────────────────────────
        private const val SMOOTHING = 0.45f
        private const val MAX_TRACK_DISTANCE = 0.35f // normalized; beyond = re-acquire
    }

    /** A detection with its per-frame geometry precomputed in [setResults]. */
    private class PreparedItem(
        val det: Detection,
        /** Outline ring bitmap (dilated mask minus mask), null = none. */
        val ring: Bitmap?,
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
    private val ringStampPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringCutPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val temporalPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = RING_TEMPORAL_ALPHA
    }
    private val ringDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
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

    // ── label-anchor smoothing (per class id) ────────────────────────────────
    private class Track(var labelAnchor: Float?)
    private val tracks = mutableMapOf<Int, Track>()

    // ── ring scratch pool (no per-frame bitmap churn) ─────────────────────────
    private val ringPool = ArrayList<Bitmap>(4)
    private var ringPoolUsed = 0

    /** Last frame's composited ring per class id (temporal smoothing source). */
    private val prevRings = HashMap<Int, Bitmap>()

    private fun obtainRing(w: Int, h: Int): Bitmap {
        while (ringPoolUsed >= ringPool.size) {
            ringPool.add(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        }
        val bmp = ringPool[ringPoolUsed]
        return if (bmp.width != w || bmp.height != h) {
            // reconfigure() cannot grow a bitmap - replace it instead.
            val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            ringPool[ringPoolUsed] = fresh
            fresh
        } else {
            bmp
        }.also { ringPoolUsed++ }
    }

    private fun releaseUnusedRings() {
        while (ringPool.size > ringPoolUsed) {
            ringPool.removeAt(ringPool.size - 1).recycle()
        }
    }

    /** Called once per inference result: precomputes rings + anchors, redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight
        ringPoolUsed = 0

        val seenClasses = mutableSetOf<Int>()
        val items = ArrayList<PreparedItem>(results.size)
        for (det in results) {
            val mask = det.maskBitmap
            var ring: Bitmap? = null
            var anchor: Float? = null

            if (mask != null) {
                val firstOfClass = seenClasses.add(det.classId)
                if (showSmoothOutline) {
                    ring = buildRing(det.classId, mask)
                }
                val rawTop = rawTopNorm(mask)
                anchor = if (firstOfClass) {
                    emaAnchor(det.classId, rawTop)
                } else {
                    rawTop
                }
            }
            items.add(PreparedItem(det, ring, anchor))
        }
        prepared = items
        releaseUnusedRings()
        invalidate()
    }

    fun clear() {
        prepared = emptyList()
        ringPoolUsed = 0
        releaseUnusedRings()
        releasePrevRings()
        invalidate()
    }

    /** Forgets the label-anchor smoothing state (e.g. when the model changes). */
    fun resetSmoothing() {
        tracks.clear()
        prepared = emptyList()
        ringPoolUsed = 0
        releaseUnusedRings()
        releasePrevRings()
        invalidate()
    }

    private fun releasePrevRings() {
        prevRings.values.forEach { it.recycle() }
        prevRings.clear()
    }

    // ── ring construction: dilate the silhouette, subtract the mask ───────────

    /**
     * Builds the outline LINE as the ring between two dilations of the mask:
     *   line = dilate(mask, RING_OUT_PX) - dilate(mask, RING_IN_PX)
     * (An earlier attempt derived the inner half via "erosion" with
     * sequential DST_OUT - mathematically wrong: the union of all inward
     * shifts is itself a dilation, so that step erased the whole canvas.
     * Two-radius subtraction is equally cheap and provably correct.)
     *
     * The band hugs the mask's own boundary EXACTLY - fingers, corners, thin
     * protrusions included - and sub-pixel stamp offsets with bilinear
     * filtering feather its edges. Finally it is unioned with a faded copy of
     * last frame's ring so single-frame edge flicker melts away.
     */
    private fun buildRing(classId: Int, mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height

        // Outer dilation (this bitmap becomes the final ring).
        val ring = obtainRing(w, h)
        val c = Canvas(ring)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        stampDilation(c, mask, RING_OUT_PX)

        // Inner dilation on a second scratch bitmap.
        val inner = obtainRing(w, h)
        val ci = Canvas(inner)
        ci.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        stampDilation(ci, mask, RING_IN_PX)

        // line = outer - inner.
        c.drawBitmap(inner, 0f, 0f, ringCutPaint)

        // Temporal blend with last frame's ring.
        prevRings[classId]?.let { prev ->
            if (prev.width == w && prev.height == h) {
                c.drawBitmap(prev, 0f, 0f, temporalPaint)
            }
        }
        storePrevRing(classId, ring)
        return ring
    }

    /** Stamps [mask] in [RING_DIRECTIONS] directions at [radius] (dilation). */
    private fun stampDilation(c: Canvas, mask: Bitmap, radius: Float) {
        for (i in 0 until RING_DIRECTIONS) {
            val angle = (i * 2.0 * Math.PI) / RING_DIRECTIONS
            c.drawBitmap(
                mask,
                (cos(angle) * radius).toFloat(),
                (sin(angle) * radius).toFloat(),
                ringStampPaint,
            )
        }
    }

    /** Keeps a copy of [ring] as next frame's temporal-blend source. */
    private fun storePrevRing(classId: Int, ring: Bitmap) {
        val prev = prevRings.getOrPut(classId) {
            Bitmap.createBitmap(ring.width, ring.height, Bitmap.Config.ARGB_8888)
        }
        if (prev.width != ring.width || prev.height != ring.height) {
            prev.recycle()
            prevRings[classId] =
                Bitmap.createBitmap(ring.width, ring.height, Bitmap.Config.ARGB_8888)
        }
        val target = prevRings[classId]!!
        val pc = Canvas(target)
        pc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        pc.drawBitmap(ring, 0f, 0f, ringStampPaint)
    }

    /** EMA-blends the label anchor (top edge Y, normalized) across frames. */
    private fun emaAnchor(classId: Int, raw: Float?): Float? {
        raw ?: return null
        val prev = tracks[classId]?.labelAnchor
        val smoothed = if (prev == null || abs(raw - prev) > MAX_TRACK_DISTANCE) {
            raw
        } else {
            prev + (raw - prev) * (1f - SMOOTHING)
        }
        tracks.getOrPut(classId) { Track(null) }.labelAnchor = smoothed
        return smoothed
    }

    /** Topmost opaque row of the mask as a 0..1 fraction of its height. */
    private fun rawTopNorm(mask: Bitmap): Float? {
        val w = mask.width
        val px = IntArray(w)
        for (y in 0 until mask.height) {
            mask.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (px[x] != 0) return y.toFloat() / mask.height
            }
        }
        return null
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

                // The mask is ALWAYS drawn in full - nothing is ever clipped,
                // so the mesh can never be cut off anywhere.
                drawTintedMask(canvas, mask, maskRect, classColor)

                item.ring?.let { ring ->
                    ringDrawPaint.colorFilter = colorFilterFor(
                        DetectionStyle.vibrantFor(classColor)
                    )
                    ringDrawPaint.alpha = 255
                    canvas.drawBitmap(ring, null, maskRect, ringDrawPaint)
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
