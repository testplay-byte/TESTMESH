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
 *  - MaskGeometry (pure, unit-tested Kotlin) computes, for every mask, a
 *    second small bitmap ([Detection.outlineBitmap]): an alpha ramp over
 *    the chamfer DISTANCE TO the mesh silhouette (the MESH_ALPHA_CUTOFF
 *    level set), peaking at 255 AT the boundary and straddling it (half
 *    in, half out) with an ADAPTIVE width - narrower around small
 *    components, floored at a minimum, full width for large ones (the
 *    "small object grows a big mesh" fix). So:
 *      * the band and the mesh come from ONE threshold - no gap and no
 *        overlap between the layers is representable;
 *      * double lines, seams, and clip/stroke disagreement are
 *        unrepresentable;
 *      * distance-based ramps are solid around corners and diagonals by
 *        construction (the earlier blur-feather harvest died exactly there
 *        and rendered as a dotted line);
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
 *  1. Mesh AND outline are each composited into the offscreen buffer at
 *     FULL alpha and blitted ONCE at their settings value (maskAlpha /
 *     outlineAlpha), scoped to the dirty union rect. One blit per layer
 *     is what makes the opacity sliders exact for any object count:
 *     same-colour masks are idempotent in the buffer (the ten-hands
 *     bug), and outline ramps saturate at 255 instead of summing at
 *     crossings (the "settings combine with multiple objects" report).
 *  2. Optional bounding box + corner brackets (user toggle).
 *  3. Label chip always anchored to the box top (both toggle states).
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

        // Screen px added around each bbox when computing the dirty-union
        // rect for the composite blit. Must cover the widest possible
        // outline extent (RING_OUT ~2 coarse texels + falloff ~36px) plus
        // decode feather, so the composited outline is never clipped.
        private const val CONTENT_PAD_PX = 48f

        /** Default translucency of the tinted mesh layer (settings slider). */
        private const val DEFAULT_MASK_ALPHA = 110

        /** Default opacity of the outline band (settings slider). */
        private const val DEFAULT_OUTLINE_ALPHA = 255
    }

    /**
     * Mesh tint opacity, 0..255 (settings "Mesh Opacity"). Applied exactly
     * once, as the composite blit alpha - never per-mask, never compounded.
     * Changing it redraws immediately (paused gallery too).
     */
    var maskAlpha: Int = DEFAULT_MASK_ALPHA
        set(value) {
            val v = value.coerceIn(0, 255)
            if (field != v) { field = v; invalidate() }
        }

    /**
     * Outline band opacity, 0..255 (settings "Outline Opacity"). The band's
     * band is a flat-top sharp line with only a ~1.5-texel feather (decode
     * side), so at 255 it reads as a crisp, fully-saturated border.
     */
    var outlineAlpha: Int = DEFAULT_OUTLINE_ALPHA
        set(value) {
            val v = value.coerceIn(0, 255)
            if (field != v) { field = v; invalidate() }
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
    // buffer is blitted once at maskAlpha. SRC_OVER of opaque same-colour
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

    /**
     * Called when the old results are dropped, so their mask/outline
     * bitmaps can go back to the decode pool (swap-on-return; wired by
     * MainActivity to ModelManager.returnPrevious).
     */
    var onReleaseResults: ((List<Detection>) -> Unit)? = null

    /** Called once per inference result: caches results, then redraws. */
    fun setResults(results: List<Detection>, inputWidth: Int, inputHeight: Int) {
        val perfT = Perf.start()
        // Return the PREVIOUS frame's bitmaps first - they are off-screen
        // the moment `prepared` is replaced, and onDraw cannot interleave
        // with this (same thread).
        val prev = prepared
        if (prev.isNotEmpty()) onReleaseResults?.invoke(prev)
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
        val prev = prepared
        if (prev.isNotEmpty()) onReleaseResults?.invoke(prev)
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

        // ── MESH + OUTLINE: ONE idempotent blit per layer ───────────────────
        // Both layers are drawn at FULL alpha into the offscreen buffer and
        // blitted ONCE at their settings value. This is what makes the
        // sliders EXACT for any number of objects (device report: 10% mesh
        // read as ~60% with six hands, outline width "combined" at
        // overlaps):
        //  - mesh: overlapping masks are same-colour -> SRC_OVER idempotent
        //    (never compounds toward opacity), then a single blit at
        //    maskAlpha, so 10% is 10% whether one mask or six;
        //  - outline: drawn per-detection DIRECTLY it stacked at crossings
        //    (ramp tails summed -> wider/brighter line around groups); in
        //    the buffer the peaks saturate at 255 and the single blit at
        //    outlineAlpha applies the setting exactly once.
        // The rect-based overlap test that used to pick a "fast route" for
        // the mesh is gone: full-frame mask rasters can bleed outside their
        // bbox, so non-overlap could never be proven cheaply - correctness
        // first, cost is bounded by the dirty union (bbox + pad).
        if (contentRects.isNotEmpty()) {
            val dirty = unionRect(contentRects)
            if (dirty.intersect(0f, 0f, width.toFloat(), height.toFloat())) {
                if (maskAlpha > 0) {
                    // Pass 1 - meshes at full alpha.
                    val bc = meshBuffer(dirty)
                    bc.clipRect(dirty)
                    for (i in items.indices) {
                        val mask = items[i].maskBitmap ?: continue
                        drawTint(bc, mask, maskRectsPerItem[i],
                            DetectionStyle.colorFor(items[i].classId))
                    }
                    meshBlitPaint.alpha = maskAlpha
                    blitSrcRect.set(Math.round(dirty.left), Math.round(dirty.top),
                        Math.round(dirty.right), Math.round(dirty.bottom))
                    canvas.drawBitmap(meshBitmap!!, blitSrcRect, dirty, meshBlitPaint)
                }
                if (showSmoothOutline && outlineAlpha > 0) {
                    // Pass 2 - outlines at full alpha (buffer re-cleared by
                    // meshBuffer), blitted at outlineAlpha ON TOP of the
                    // mesh that is now already on the canvas.
                    val bc = meshBuffer(dirty)
                    bc.clipRect(dirty)
                    var anyRing = false
                    for (i in items.indices) {
                        val ring = items[i].outlineBitmap ?: continue
                        outlinePaint.colorFilter = colorFilterFor(
                            DetectionStyle.vibrantFor(
                                DetectionStyle.colorFor(items[i].classId)))
                        outlinePaint.alpha = 255
                        bc.drawBitmap(ring, null, maskRectsPerItem[i], outlinePaint)
                        anyRing = true
                    }
                    if (anyRing) {
                        meshBlitPaint.alpha = outlineAlpha
                        blitSrcRect.set(Math.round(dirty.left), Math.round(dirty.top),
                            Math.round(dirty.right), Math.round(dirty.bottom))
                        canvas.drawBitmap(meshBitmap!!, blitSrcRect, dirty, meshBlitPaint)
                    }
                }
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

    /** Bounding union of a non-empty rect list (new instance). */
    private fun unionRect(rects: List<RectF>): RectF {
        val u = RectF(rects[0])
        for (i in 1 until rects.size) u.union(rects[i])
        return u
    }

    /**
     * Draws one mask tinted with the class colour at FULL alpha - the
     * caller blits the buffer once at [maskAlpha], so the setting applies
     * exactly once no matter how many masks overlap in it.
     */
    private fun drawTint(
        canvas: Canvas, mask: Bitmap, maskRect: RectF, classColor: Int,
    ) {
        maskPaint.colorFilter = colorFilterFor(classColor)
        maskPaint.alpha = 255
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
