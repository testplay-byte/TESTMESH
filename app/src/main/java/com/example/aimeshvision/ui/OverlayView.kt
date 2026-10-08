package com.example.aimeshvision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.aimeshvision.inference.Detection
import com.example.aimeshvision.util.Perf

/**
 * Full-screen canvas that maps normalized detections onto screen space.
 *
 * ── ARCHITECTURE (image-space outline, rewritten from scratch) ───────────────
 *
 * The smooth mesh outline is NOT vector geometry. It is an IMAGE-SPACE BAND:
 *
 *  - YoloPostProcessor computes, for every mask, a second small bitmap
 *    ([Detection.outlineBitmap]) from the SAME blurred alpha field the mesh
 *    is drawn from: texels whose alpha sits in [RING_LOW_ALPHA, cutoff) get
 *    a bright ramp (see smoothMaskAlpha). That zone is exactly the ring
 *    immediately OUTSIDE the visible mesh silhouette (the mesh keeps alpha
 *    >= MESH_ALPHA_CUTOFF), so:
 *      * the band's inner edge and the mesh's outer edge come from ONE
 *        threshold - they meet with no gap and no overlap by construction;
 *      * double lines, seams, and clip/stroke disagreement are unrepresentable;
 *      * smoothness is the field's own smoothed gradient, bilinearly upscaled
 *        (~8 screen px per texel) - no pixel staircase can exist;
 *      * the band is a per-pixel property - closed around every blob and
 *        speckle-filtered at the source, with no contour tracing at all.
 *  - The band is drawn like the mesh: one drawBitmap per detection with a
 *    vibrant SRC_IN color filter, after the translucent mesh layer.
 *
 * This replaces ~900 lines of contour extraction (flood fill, tracing,
 * Douglas-Peucker, noise-peak heuristics, corner classification, arc-length
 * resampling, phase alignment, EMA, bezier paths, Path.op clips) - a chain
 * of ~20 tunables that compensated for each other and produced, across many
 * device rounds: missing lines, double lines, spikes chasing noise, jitter.
 *
 * Rendering per frame ([onDraw]):
 *  1. Mesh: direct translucent draw for non-overlapping masks; the offscreen
 *     composite (full-alpha buffer blitted once at MASK_ALPHA) only when
 *     masks OVERLAP, scoped to the dirty union rect - overlapping alpha can
 *     never compound toward opacity (the ten-hands solid-mesh bug).
 *  2. Outline (toggle): one band bitmap draw per detection, direct.
 *  3. Optional bounding box + corner brackets (user toggle).
 *  4. Label chip always anchored to the box top (both toggle states).
 *
 * Split & detect crops map through [maskRectFor] (Detection carries the
 * normalized source rect its mask covers).
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

        /** Translucency of the tinted mesh layer. */
        private const val MASK_ALPHA = 110

        /**
         * Opacity of the outline band. The band's peak alpha already carries
         * a gradient ramp (decode side), so this reads as a bright, soft,
         * fully-saturated border at full strength.
         */
        private const val OUTLINE_ALPHA = 255

        // Screen px added around each bbox when computing mask content rects
        // (decode feather + minor spill) for the overlap fast-path test.
        private const val CONTENT_PAD_PX = 24f
    }

    // ── paints ──────────────────────────────────────────────────────────────
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
    /** Band draw: filtering on, colour/alpha set per detection. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private var prepared: List<Detection> = emptyList()
    private var sourceImageWidth: Int = 640
    private var sourceImageHeight: Int = 640

    /** User-toggleable rendering options (settings sheet). */
    var showBoxes: Boolean = true

    /** Outline band toggle: redraws on BOTH edges (paused gallery too). */
    var showSmoothOutline: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    // ── offscreen mesh composite (overlap route only) ───────────────────────
    // Masks are drawn into this buffer at FULL alpha (colour baked), then the
    // buffer is blitted once at MASK_ALPHA. SRC_OVER of opaque same-colour
    // pixels is idempotent, so overlapping instances can no longer compound
    // 110 -> 163 -> 197 toward opacity. Allocated lazily, screen-sized,
    // reused; only the dirty union rect is cleared/blitted.
    private var meshBitmap: Bitmap? = null
    private var meshCanvas: Canvas? = null
    private val meshBlitPaint = Paint()
    private val blitSrcRect = Rect()
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** Called once per inference result: caches results, then redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        val perfT = Perf.start()
        sourceImageWidth = inputWidth
        sourceImageHeight = inputHeight
        prepared = results
        invalidate()
        Perf.log("overlay-setResults", perfT)
    }

    /**
     * Called when the outline toggle turns ON: the band bitmaps are already
     * part of every decode (computed unconditionally, ~64 KB per mask on the
     * inference thread), so this only forces a redraw - the outline appears
     * immediately, even while the gallery is paused.
     */
    fun onOutlineEnabled() {
        invalidate()
    }

    fun clear() {
        prepared = emptyList()
        invalidate()
    }

    /**
     * Kept for callers that reset temporal state (model change). The redesign
     * has NO temporal state - the band is a pure per-frame image - so this is
     * simply a clear.
     */
    fun resetSmoothing() = clear()

    // ── drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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

        // Per-item mask rect (crop rect for split & detect refinements, full
        // frame otherwise) + content rects (bbox + feather) for routing.
        val maskRectsPerItem = ArrayList<RectF>(items.size)
        val contentRects = ArrayList<RectF>(items.size)
        for (item in items) {
            maskRectsPerItem.add(maskRectFor(item, maskRect))
            if (item.maskBitmap == null) continue
            contentRects.add(RectF(
                item.boundingBox.left * scaledW + offsetX - CONTENT_PAD_PX,
                item.boundingBox.top * scaledH + offsetY - CONTENT_PAD_PX,
                item.boundingBox.right * scaledW + offsetX + CONTENT_PAD_PX,
                item.boundingBox.bottom * scaledH + offsetY + CONTENT_PAD_PX,
            ))
        }

        // ── MESH LAYER ──────────────────────────────────────────────────────
        // Alpha compounding only happens where two masks OVERLAP; a
        // non-overlapping set drawn directly at MASK_ALPHA is pixel-
        // equivalent to the composite result, so the offscreen buffer (full
        // screen clear + blit) is only paid for when it is needed.
        if (contentRects.isEmpty() || !haveOverlap(contentRects)) {
            // Fast route - direct translucent draws, zero offscreen work.
            for (i in items.indices) {
                val mask = items[i].maskBitmap ?: continue
                drawTint(canvas, mask, maskRectsPerItem[i],
                    DetectionStyle.colorFor(items[i].classId), fullAlpha = false)
            }
        } else {
            // Composite route - union blit at MASK_ALPHA: overlap regions can
            // never compound toward opacity (the ten-hands solid-mesh bug).
            val dirty = unionRect(contentRects)
            if (dirty.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                val bc = meshBuffer(dirty)
                bc.clipRect(dirty)
                for (i in items.indices) {
                    val mask = items[i].maskBitmap ?: continue
                    drawTint(bc, mask, maskRectsPerItem[i],
                        DetectionStyle.colorFor(items[i].classId), fullAlpha = true)
                }
                meshBlitPaint.alpha = MASK_ALPHA
                blitSrcRect.set(Math.round(dirty.left), Math.round(dirty.top),
                    Math.round(dirty.right), Math.round(dirty.bottom))
                canvas.drawBitmap(meshBitmap!!, blitSrcRect, dirty, meshBlitPaint)
            }
        }

        // ── OUTLINE LAYER (image-space band) ────────────────────────────────
        // One band bitmap per detection, drawn directly (like the old stroke:
        // crossings brighten slightly - the expected look for a bright border).
        // The band already sits at the mesh's outer edge by construction, so
        // no clip and no path building exist in this pipeline.
        if (showSmoothOutline) {
            for (i in items.indices) {
                val ring = items[i].outlineBitmap ?: continue
                outlinePaint.colorFilter = colorFilterFor(
                    DetectionStyle.vibrantFor(DetectionStyle.colorFor(items[i].classId)))
                outlinePaint.alpha = OUTLINE_ALPHA
                canvas.drawBitmap(ring, null, maskRectsPerItem[i], outlinePaint)
            }
        }

        // ── BOXES, CORNERS, LABELS ──────────────────────────────────────────
        for (i in items.indices) {
            val det = items[i]
            val classColor = DetectionStyle.colorFor(det.classId)

            val left = det.boundingBox.left * scaledW + offsetX
            val top = det.boundingBox.top * scaledH + offsetY
            val right = det.boundingBox.right * scaledW + offsetX
            val bottom = det.boundingBox.bottom * scaledH + offsetY
            val screenRect = RectF(left, top, right, bottom)

            // Labels are ALWAYS anchored to the box top - with boxes hidden
            // the chip shows exactly where it would appear with boxes shown.
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

    /**
     * Returns the composite buffer, cleared ONLY inside [dirty] (the full
     * ~10 MB screen clear every frame was a measurable cost). Screen-sized,
     * reused, recreated only on view-size change.
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

    /**
     * Screen rect covering [det]'s mask bitmap. Full-frame masks (default
     * mask* fields) map over the whole frame rect; split & detect refine
     * detections carry a crop rect, so their mask maps over just that window.
     */
    private fun maskRectFor(det: Detection, full: RectF): RectF {
        if (det.maskLeft == 0f && det.maskTop == 0f &&
            det.maskWidth == 1f && det.maskHeight == 1f
        ) return full
        return RectF(
            full.left + det.maskLeft * full.width(),
            full.top + det.maskTop * full.height(),
            full.left + (det.maskLeft + det.maskWidth) * full.width(),
            full.top + (det.maskTop + det.maskHeight) * full.height(),
        )
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
     * Draws one mask tinted with the class colour.
     *
     * @param fullAlpha true = write at full alpha for the composite route,
     *        which applies MASK_ALPHA once at blit time; false = apply
     *        MASK_ALPHA directly (fast route).
     */
    private fun drawTint(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
        fullAlpha: Boolean,
    ) {
        maskPaint.colorFilter = colorFilterFor(classColor)
        maskPaint.alpha = if (fullAlpha) 255 else MASK_ALPHA
        canvas.drawBitmap(mask, null, maskRect, maskPaint)
    }

    // ── color + labels ──────────────────────────────────────────────────────

    private val filterCache = HashMap<Int, android.graphics.ColorFilter>()

    private fun colorFilterFor(color: Int): android.graphics.ColorFilter =
        filterCache.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

    /** Class id -> uppercase label (avoids per-frame string churn). */
    private val labelCache = HashMap<Int, String>()

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
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom - len, rect.left, rect.bottom, cornerPaint)
        // bottom-right
        canvas.drawLine(rect.right - len, rect.bottom, rect.right, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom - len, rect.right, rect.bottom, cornerPaint)
    }

    /** Draws the label chip at [anchorTop] (the box top). */
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
