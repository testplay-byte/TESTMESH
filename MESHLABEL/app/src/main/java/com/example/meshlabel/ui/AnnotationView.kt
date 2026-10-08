package com.example.meshlabel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.meshlabel.model.Pt
import com.example.meshlabel.model.Shape
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Annotation canvas: draws the fit-center image plus everything the user
 * creates on it - shapes, polygon draft, Magic Touch strokes and the
 * segmentation preview mask.
 *
 * Coordinate model (the LabelMe contract):
 *  - all data ([Shape.points], strokes, draft) lives in ORIGINAL-IMAGE
 *    pixel coordinates; this view only maps to screen with fit-center and
 *    back, so zoom-free rotation/resizing can never corrupt annotations;
 *  - the view MUTATES shape points during vertex drags and reports
 *    [onBeforeVertexDrag] (undo snapshot) / [onShapesEdited] (save badge)
 *    so the activity owns history and persistence.
 *
 * Modes:
 *  - [Mode.EDIT]: tap selects a shape, drag its handles moves vertices,
 *    with `drawMode` on, taps append draft points (tap the enlarged first
 *    point to close the polygon);
 *  - [Mode.MAGIC]: each gesture is one scribble; polarity comes from
 *    [brushPositive] (add/remove selection). Committed strokes render
 *    green/red; [onStrokeCommitted] asks the activity to re-segment.
 */
class AnnotationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { EDIT, MAGIC }

    /** One Magic Touch scribble in image pixels. */
    data class StrokePts(val points: MutableList<Pt> = mutableListOf(), var positive: Boolean = true)

    // ── data (activity-owned lists; view mutates points in place) ──────────
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }

    /**
     * Original (file) image dimensions - the annotation space. The decoded
     * [image] may be downscaled for memory (ProjectStore.MAX_IMAGE_DIM);
     * shapes/strokes/draft are ALWAYS stored in original pixels, and this
     * view converts orig<->bitmap at the mapping boundary. 0 = unset ->
     * falls back to the bitmap's own size (no downscale happened).
     */
    var origWidth: Int = 0
    var origHeight: Int = 0

    private fun origW(): Int = origWidth.takeIf { it > 0 } ?: (image?.width ?: 1)
    private fun origH(): Int = origHeight.takeIf { it > 0 } ?: (image?.height ?: 1)

    var shapes: MutableList<Shape> = mutableListOf()
        set(value) { field = value; selected = -1; invalidate() }

    var selected: Int = -1
        set(value) { if (field != value) { field = value; invalidate() } }

    var mode: Mode = Mode.EDIT
        set(value) { if (field != value) { field = value; clearDraft(); invalidate() } }

    /** True while polygon drafting is armed (taps add points). */
    var drawMode: Boolean = false
        set(value) { field = value; clearDraft(); invalidate() }

    /** Polarity for the next Magic scribble (set by the Add/Remove toggle). */
    var brushPositive: Boolean = true

    /** Committed Magic scribbles (all of them; re-segment uses all). */
    val strokes = mutableListOf<StrokePts>()

    /** Full-image-size overlay bitmap of the current selection preview. */
    var previewMask: Bitmap? = null
        set(value) { field = value; invalidate() }

    // ── callbacks to the activity ───────────────────────────────────────────
    var onStrokeCommitted: (() -> Unit)? = null
    var onShapesEdited: (() -> Unit)? = null
    var onBeforeVertexDrag: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null
    var onDraftPointAdded: ((Int) -> Unit)? = null
    var onDraftCommitted: ((List<Pt>) -> Unit)? = null

    // ── draft (in-progress polygon, image px) ───────────────────────────────
    private val draft = mutableListOf<Pt>()
    private var draftTail: PointF? = null

    // ── gesture state ───────────────────────────────────────────────────────
    private var dragShape = -1
    private var dragVertex = -1
    private var dragMoved = false
    private var currentStroke: StrokePts? = null

    // ── paints ──────────────────────────────────────────────────────────────
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.BLACK
    }
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f)
        pathEffect = DashPathEffect(floatArrayOf(dp(14f), dp(10f)), 0f)
        color = Color.WHITE
    }
    private val draftDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scribblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** Class-like palette; index by shape position. */
    private val palette = intArrayOf(
        0xFF00E676.toInt(), 0xFF40C4FF.toInt(), 0xFFFFD740.toInt(), 0xFFFF4081.toInt(),
        0xFFB388FF.toInt(), 0xFF18FFFF.toInt(), 0xFFFF6E40.toInt(), 0xFF69F0AE.toInt(),
    )

    private val imageRect = RectF()
    private val tmpRect = RectF()

    // ── fit-center mapping (same convention as LabelMe's displayed image) ───
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private fun updateMapping() {
        val img = image ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        scale = min(vw / img.width, vh / img.height)
        val dw = img.width * scale
        val dh = img.height * scale
        offX = (vw - dw) / 2f
        offY = (vh - dh) / 2f
        imageRect.set(offX, offY, offX + dw, offY + dh)
    }

    private fun toScreen(x: Float, y: Float): PointF {
        // Original px -> bitmap px (decode downscale) -> fit-center screen.
        val img = image ?: return PointF(x, y)
        val kx = img.width.toFloat() / origW()
        val ky = img.height.toFloat() / origH()
        return PointF(offX + x * kx * scale, offY + y * ky * scale)
    }

    /** Screen -> ORIGINAL-image pixels (clamped to the original bounds). */
    private fun toImage(sx: Float, sy: Float): Pt {
        val img = image ?: return Pt(sx, sy)
        val kx = origW().toFloat() / img.width
        val ky = origH().toFloat() / img.height
        val ix = (sx - offX) / scale * kx
        val iy = (sy - offY) / scale * ky
        return Pt(ix.coerceIn(0f, origW().toFloat()), iy.coerceIn(0f, origH().toFloat()))
    }

    fun draftSize(): Int = draft.size

    fun clearDraft() {
        draft.clear()
        draftTail = null
        invalidate()
    }

    fun selectionColor(): Int =
        if (selected in palette.indices) palette[selected] else palette[0]

    // ── drawing ─────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val img = image ?: return
        updateMapping()
        if (imageRect.isEmpty) return

        canvas.drawBitmap(img, null, imageRect, imagePaint)

        val save = canvas.save()
        canvas.clipRect(imageRect)

        // Selection preview mask (drawn under the shape outlines).
        previewMask?.let { canvas.drawBitmap(it, null, imageRect, imagePaint) }

        // Shapes: translucent fill + stroke; selected one gets handles.
        for (i in shapes.indices) {
            val s = shapes[i] ?: continue
            if (s.points.size < 2) continue
            val color = palette[i % palette.size]
            val path = Path()
            val first = toScreen(s.points[0].x, s.points[0].y)
            path.moveTo(first.x, first.y)
            for (j in 1 until s.points.size) {
                val p = toScreen(s.points[j].x, s.points[j].y)
                path.lineTo(p.x, p.y)
            }
            if (s.shapeType == "polygon" && s.points.size >= 3) path.close()
            fillPaint.color = color
            fillPaint.alpha = if (i == selected) 70 else 38
            canvas.drawPath(path, fillPaint)
            strokePaint.color = color
            strokePaint.alpha = if (i == selected) 255 else 160
            canvas.drawPath(path, strokePaint)

            if (i == selected && mode == Mode.EDIT && !drawMode) {
                handlePaint.color = Color.WHITE
                for (p in s.points) {
                    val sp = toScreen(p.x, p.y)
                    canvas.drawCircle(sp.x, sp.y, dp(7f), handlePaint)
                    canvas.drawCircle(sp.x, sp.y, dp(7f), handleRingPaint)
                }
            }
        }

        // Polygon draft: dashed edges + tail to the finger + point dots.
        if (draft.isNotEmpty()) {
            draftPaint.alpha = 255
            var prev: PointF? = null
            for (p in draft) {
                val sp = toScreen(p.x, p.y)
                if (prev != null) canvas.drawLine(prev.x, prev.y, sp.x, sp.y, draftPaint)
                prev = sp
            }
            draftTail?.let { tail ->
                prev?.let { canvas.drawLine(prev.x, prev.y, tail.x, tail.y, draftPaint) }
            }
            draftDotPaint.color = Color.WHITE
            draft.forEachIndexed { idx, p ->
                val sp = toScreen(p.x, p.y)
                canvas.drawCircle(sp.x, sp.y, if (idx == 0) dp(9f) else dp(5f), draftDotPaint)
            }
        }

        // Magic Touch scribbles: committed + in-flight, green=add/red=remove.
        if (mode == Mode.MAGIC) {
            for (s in strokes) drawStroke(canvas, s)
            currentStroke?.let { drawStroke(canvas, it) }
        }

        canvas.restoreToCount(save)
    }

    private fun drawStroke(canvas: Canvas, s: StrokePts) {
        if (s.points.isEmpty()) return
        scribblePaint.color =
            if (s.positive) 0xFF00E676.toInt() else 0xFFFF5252.toInt()
        scribblePaint.alpha = 230
        var prev: PointF? = null
        for (p in s.points) {
            val sp = toScreen(p.x, p.y)
            if (prev != null) canvas.drawLine(prev.x, prev.y, sp.x, sp.y, scribblePaint)
            else canvas.drawCircle(sp.x, sp.y, dp(4f), scribblePaint)
            prev = sp
        }
        // A single tap still reads as a positive/negative dot.
        if (s.points.size == 1) {
            prev?.let { canvas.drawCircle(it.x, it.y, dp(6f), scribblePaint) }
        }
    }

    // ── touch ───────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val raw = PointF(event.x, event.y)
                return when (mode) {
                    Mode.EDIT -> onDownEdit(raw)
                    Mode.MAGIC -> onDownMagic(raw)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = PointF(event.x, event.y)
                when (mode) {
                    Mode.EDIT -> onMoveEdit(raw)
                    Mode.MAGIC -> onMoveMagic(raw)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onUp(event.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onDownEdit(raw: PointF): Boolean {
        // 1) Grab a vertex handle (selected shape first, then the rest).
        val hit = hitVertex(raw)
        if (hit != null) {
            dragShape = hit.first
            dragVertex = hit.second
            dragMoved = false
            selected = hit.first
            onSelectionChanged?.invoke(hit.first)
            onBeforeVertexDrag?.invoke()
            invalidate()
            return true
        }
        // 2) Close the draft by tapping its enlarged first point.
        if (draft.size >= 3) {
            val first = toScreen(draft[0].x, draft[0].y)
            if (hypot(raw.x - first.x, raw.y - first.y) < dp(36f)) {
                val committed = draft.toList()
                clearDraft()
                onDraftCommitted?.invoke(committed)
                return true
            }
        }
        // 3) Drafting: append a point.
        if (drawMode) {
            draft.add(toImage(raw.x, raw.y))
            draftTail = raw
            onDraftPointAdded?.invoke(draft.size)
            invalidate()
            return true
        }
        // 4) Selection: topmost shape under the finger (iterate newest first).
        val img = toImage(raw.x, raw.y)
        var pick = -1
        for (i in shapes.indices.reversed()) {
            if (shapes[i].points.size >= 3 && shapes[i].contains(img.x, img.y)) { pick = i; break }
        }
        selected = pick
        onSelectionChanged?.invoke(pick)
        invalidate()
        return true
    }

    private fun onMoveEdit(raw: PointF) {
        if (dragShape < 0 || dragShape >= shapes.size) return
        val pts = shapes[dragShape].points
        if (dragVertex >= pts.size) return
        val img = toImage(raw.x, raw.y)
        pts[dragVertex] = img
        dragMoved = true
        invalidate()
    }

    private fun onDownMagic(raw: PointF): Boolean {
        val img = toImage(raw.x, raw.y)
        currentStroke = StrokePts(mutableListOf(img), brushPositive)
        invalidate()
        return true
    }

    private fun onMoveMagic(raw: PointF) {
        val cur = currentStroke ?: return
        val last = cur.points.lastOrNull() ?: return
        // Screen-space 8px threshold: keeps stroke point counts sane.
        val lastS = toScreen(last.x, last.y)
        if (hypot(raw.x - lastS.x, raw.y - lastS.y) < dp(8f)) return
        cur.points.add(toImage(raw.x, raw.y))
        invalidate()
    }

    private fun onUp(rose: Boolean) {
        when (mode) {
            Mode.EDIT -> {
                if (dragShape >= 0 && dragMoved) onShapesEdited?.invoke()
                dragShape = -1; dragVertex = -1; dragMoved = false
            }
            Mode.MAGIC -> {
                val cur = currentStroke ?: return
                currentStroke = null
                if (rose && cur.points.isNotEmpty() && cur.points.size >= 1) {
                    strokes.add(cur)
                    onStrokeCommitted?.invoke()
                }
                invalidate()
            }
        }
    }

    /** Vertex hit-test in screen space; selected shape's vertices win. */
    private fun hitVertex(raw: PointF): Pair<Int, Int>? {
        if (mode != Mode.EDIT || drawMode) return null
        val order = if (selected in shapes.indices) {
            listOf(selected) + shapes.indices.filter { it != selected }
        } else shapes.indices.toList()
        for (si in order) {
            val s = shapes[si]
            for (vi in s.points.indices) {
                val sp = toScreen(s.points[vi].x, s.points[vi].y)
                if (hypot(raw.x - sp.x, raw.y - sp.y) < dp(36f)) return si to vi
            }
        }
        return null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * Builds the colored preview overlay from a raw mask, downsampling to
     * at most 1024 px per axis so a 12MP mask never churns the UI thread
     * through 12M pixels (the view scales it to the image anyway).
     */
    fun setPreviewMask(mask: BooleanArray, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || mask.size < w * h) { previewMask = null; return }
        val step = max(1, maxOf(w, h) / 1024)
        val bw = (w + step - 1) / step
        val bh = (h + step - 1) / step
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val px = IntArray(bw * bh)
        for (y in 0 until bh) {
            val sy = y * step
            for (x in 0 until bw) {
                px[y * bw + x] =
                    if (mask[sy * w + x * step]) 0x6600E58A else Color.TRANSPARENT
            }
        }
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        previewMask = bmp
        invalidate()
    }

    fun clearMagic() {
        strokes.clear()
        currentStroke = null
        previewMask = null
        invalidate()
    }

    /** Drops the last committed stroke (visual half of stroke-undo). */
    fun popStroke(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.size - 1)
        invalidate()
        return true
    }
}
