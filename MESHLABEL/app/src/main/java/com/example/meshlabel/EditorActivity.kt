package com.example.meshlabel

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.meshlabel.model.Pt
import com.example.meshlabel.model.Shape
import com.example.meshlabel.seg.InteractiveSegmenterManager
import com.example.meshlabel.seg.MaskToPolygon
import com.example.meshlabel.store.ProjectStore
import com.example.meshlabel.ui.AnnotationView
import java.util.ArrayDeque

/**
 * Annotation editor: LabelMe-parity polygon editing + MediaPipe Magic
 * Touch scribble segmentation, saved as exact LabelMe JSON.
 *
 * Responsibilities kept OUT of the view:
 *  - history (undo/redo = deep-copied shape snapshots, cap 50) - vertex
 *    drags snapshot on grab and commit only if the finger actually moved;
 *  - labels (prompt on polygon creation, remembered as the next default);
 *  - segmentation (runs on [InteractiveSegmenterManager]'s thread, stale
 *    results dropped by requestId, mask converted to a polygon on demand);
 *  - persistence (ProjectStore.save writes image+JSON side by side).
 *
 * Coordinate spaces: shapes/strokes live in ORIGINAL-image pixels (the
 * LabelMe space); the decoded bitmap may be smaller (memory cap) and the
 * segmenter mask is bitmap-sized - every crossing point converts explicitly
 * via [origToBitmap]/[bitmapToOrig].
 */
class EditorActivity : AppCompatActivity(), InteractiveSegmenterManager.Listener {

    companion object {
        const val EXTRA_PROJECT = "project_name"
        private const val MAX_UNDO = 50
        private const val DEFAULT_LABEL = "object"
    }

    private lateinit var project: ProjectStore.Project
    private var bitmap: android.graphics.Bitmap? = null
    private var origW = 0
    private var origH = 0

    private lateinit var view: AnnotationView
    private lateinit var statusText: TextView
    private lateinit var btnSave: TextView

    /** Annotation data (original-image pixels). */
    private val shapes = mutableListOf<Shape>()
    private val undoStack = ArrayDeque<List<Shape>>()
    private val redoStack = ArrayDeque<List<Shape>>()
    private var pendingDragSnapshot: List<Shape>? = null
    private var lastLabel = DEFAULT_LABEL
    private var dirty = false

    private lateinit var segmenter: InteractiveSegmenterManager
    private var latestRequest = 0

    /** Last accepted mask (BITMAP-sized) waiting for "▶ Polygon". */
    private var currentMask: BooleanArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val name = intent.getStringExtra(EXTRA_PROJECT)
        val found = ProjectStore.list(this).find { it.name == name }
        if (found == null) {
            Toast.makeText(this, "Project not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        project = found

        // Original dimensions straight from the file header (the decoded
        // bitmap may be downsampled - annotation space must not be).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(project.imageFile.path, bounds)
        origW = bounds.outWidth
        origH = bounds.outHeight
        val bmp = ProjectStore.loadBitmap(project)
        if (bmp == null || origW <= 0 || origH <= 0) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        bitmap = bmp

        val (loaded, error) = ProjectStore.loadShapes(project)
        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle("Annotation could not be fully loaded")
                .setMessage(error)
                .setPositiveButton("Continue", null)
                .show()
        }
        shapes.addAll(loaded)
        // Remember the most common label as the next default.
        shapes.lastOrNull()?.let { lastLabel = it.label }

        view = findViewById(R.id.annotationView)
        statusText = findViewById(R.id.statusText)
        btnSave = findViewById(R.id.btnSave)
        view.image = bmp
        view.origWidth = origW
        view.origHeight = origH
        view.shapes = shapes

        segmenter = InteractiveSegmenterManager(this)
        wireCallbacks()
        wireButtons()
        updateTitle()
        updateStatus("Tap a shape to select · ＋ Polygon to draw")
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    private fun wireCallbacks() {
        view.onBeforeVertexDrag = { pendingDragSnapshot = snapshot() }
        view.onShapesEdited = {
            pendingDragSnapshot?.let {
                undoStack.addLast(it)
                redoStack.clear()
                trimHistory()
                pendingDragSnapshot = null
            }
            markDirty()
            updateStatus("Vertex moved")
        }
        view.onSelectionChanged = { idx ->
            updateStatus(
                if (idx in shapes.indices) "Selected: ${shapes[idx].label} (#${idx + 1})"
                else "Selection cleared"
            )
        }
        view.onDraftPointAdded = { n ->
            updateStatus("Drawing polygon: $n points — tap the FIRST dot to close")
        }
        view.onDraftCommitted = { pts -> promptLabel(lastLabel) { label -> commitDraft(label, pts) } }
        view.onStrokeCommitted = { runSegmentation() }
    }

    private fun wireButtons() {
        findViewById<TextView>(R.id.btnBack).setOnClickListener { attemptBack() }
        btnSave.setOnClickListener { save() }
        findViewById<TextView>(R.id.btnModeEdit).setOnClickListener { setMode(AnnotationView.Mode.EDIT) }
        findViewById<TextView>(R.id.btnModeMagic).setOnClickListener { setMode(AnnotationView.Mode.MAGIC) }
        findViewById<TextView>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<TextView>(R.id.btnRedo).setOnClickListener { redo() }

        // Polygon tools.
        findViewById<TextView>(R.id.btnDraw).setOnClickListener {
            view.drawMode = !view.drawMode
            findViewById<TextView>(R.id.btnDraw).setTextColor(
                getColor(if (view.drawMode) R.color.lime else R.color.white)
            )
            if (view.drawMode) updateStatus("Tap to add points, tap the first dot to close")
        }
        findViewById<TextView>(R.id.btnCancelDraft).setOnClickListener {
            view.drawMode = false
            findViewById<TextView>(R.id.btnDraw).setTextColor(getColor(R.color.white))
            updateStatus("Draft cancelled")
        }
        findViewById<TextView>(R.id.btnShapes).setOnClickListener { showShapesDialog() }

        // Magic Touch tools.
        findViewById<TextView>(R.id.btnBrushAdd).setOnClickListener { setBrush(true) }
        findViewById<TextView>(R.id.btnBrushRemove).setOnClickListener { setBrush(false) }
        findViewById<TextView>(R.id.btnStrokeUndo).setOnClickListener {
            if (view.popStroke()) {
                if (view.strokes.isEmpty()) {
                    currentMask = null
                    view.previewMask = null
                } else {
                    runSegmentation()
                }
            }
        }
        findViewById<TextView>(R.id.btnClearMagic).setOnClickListener {
            view.clearMagic()
            currentMask = null
            updateStatus("Strokes cleared")
        }
        findViewById<TextView>(R.id.btnConvert).setOnClickListener { convertMaskToPolygon() }
    }

    private fun setBrush(positive: Boolean) {
        view.brushPositive = positive
        findViewById<TextView>(R.id.btnBrushAdd).setTextColor(
            getColor(if (positive) R.color.lime else R.color.slate)
        )
        findViewById<TextView>(R.id.btnBrushRemove).setTextColor(
            getColor(if (!positive) R.color.red else R.color.slate)
        )
        updateStatus(if (positive) "Brush: ADD — draw to select more" else "Brush: REMOVE — draw to deselect")
    }

    private fun setMode(mode: AnnotationView.Mode) {
        view.mode = mode
        view.drawMode = false
        findViewById<TextView>(R.id.btnDraw).setTextColor(getColor(R.color.white))
        val edit = mode == AnnotationView.Mode.EDIT
        findViewById<android.view.View>(R.id.editActions).visibility =
            if (edit) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.magicActions).visibility =
            if (edit) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.btnModeEdit).setTextColor(
            getColor(if (edit) R.color.lime else R.color.slate)
        )
        findViewById<TextView>(R.id.btnModeMagic).setTextColor(
            getColor(if (!edit) R.color.lime else R.color.slate)
        )
        updateStatus(
            if (edit) "Edit: tap to select, drag handles, ＋ Polygon to draw"
            else "Magic Touch: scribble with Add/Remove, then ▶ Polygon"
        )
    }

    // ── history ─────────────────────────────────────────────────────────────

    private fun snapshot(): List<Shape> = shapes.map { it.deepCopy() }

    private fun trimHistory() {
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        while (redoStack.size > MAX_UNDO) redoStack.removeFirst()
    }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        redoStack.clear()
        trimHistory()
    }

    private fun undo() {
        if (undoStack.isEmpty()) { updateStatus("Nothing to undo"); return }
        redoStack.addLast(snapshot())
        applyShapes(undoStack.removeLast())
        updateStatus("Undone")
    }

    private fun redo() {
        if (redoStack.isEmpty()) { updateStatus("Nothing to redo"); return }
        undoStack.addLast(snapshot())
        applyShapes(redoStack.removeLast())
        updateStatus("Redone")
    }

    private fun applyShapes(next: List<Shape>) {
        shapes.clear()
        shapes.addAll(next)
        view.shapes = shapes
        markDirty()
        updateTitle()
    }

    // ── polygon creation / labels ───────────────────────────────────────────

    private fun commitDraft(label: String, pts: List<Pt>) {
        if (pts.size < 3) {
            updateStatus("A polygon needs at least 3 points")
            return
        }
        pushUndo()
        shapes.add(Shape(label, pts.toMutableList()))
        lastLabel = label
        view.shapes = shapes
        view.drawMode = false
        findViewById<TextView>(R.id.btnDraw).setTextColor(getColor(R.color.white))
        markDirty()
        updateStatus("Created \"$label\" (${pts.size} points)")
    }

    /** Label prompt with the previous label as default (LabelMe-style). */
    private fun promptLabel(initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setInputType(InputType.TYPE_CLASS_TEXT)
            setText(initial)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Shape label")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val label = input.text.toString().trim().ifEmpty { DEFAULT_LABEL }
                onOk(label)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShapesDialog() {
        if (shapes.isEmpty()) {
            updateStatus("No shapes yet — draw a polygon or use Magic Touch")
            return
        }
        val items = shapes.mapIndexed { i, s ->
            "${s.label}  ·  ${s.shapeType}  ·  ${s.points.size} pts (#${i + 1})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Shapes (pick one for actions)")
            .setItems(items) { _, which -> showShapeActions(which) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showShapeActions(idx: Int) {
        if (idx !in shapes.indices) return
        AlertDialog.Builder(this)
            .setTitle("Shape #${idx + 1}: ${shapes[idx].label}")
            .setItems(arrayOf("Select on canvas", "Rename…", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        view.selected = idx
                        updateStatus("Selected: ${shapes[idx].label}")
                    }
                    1 -> promptLabel(shapes[idx].label) { label ->
                        pushUndo()
                        shapes[idx].label = label
                        lastLabel = label
                        view.shapes = shapes
                        view.selected = idx
                        markDirty()
                        updateStatus("Renamed to \"$label\"")
                    }
                    2 -> {
                        pushUndo()
                        shapes.removeAt(idx)
                        view.shapes = shapes
                        markDirty()
                        updateStatus("Shape deleted")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── magic touch segmentation ────────────────────────────────────────────

    private fun runSegmentation() {
        val bmp = bitmap ?: return
        if (view.strokes.isEmpty()) {
            currentMask = null
            view.previewMask = null
            return
        }
        // Strokes are original px; the segmenter runs on the decoded bitmap.
        val kx = bmp.width.toFloat() / origW
        val ky = bmp.height.toFloat() / origH
        val specs = view.strokes.map { s ->
            InteractiveSegmenterManager.Spec(
                points = s.points.map { Pt(it.x * kx, it.y * ky) },
                positive = s.positive,
            )
        }
        updateStatus("Segmenting…")
        latestRequest = segmenter.segment(bmp, specs, this)
    }

    override fun onSegmentResult(requestId: Int, mask: BooleanArray, width: Int, height: Int) {
        if (requestId != latestRequest) return   // stale result
        currentMask = mask
        view.setPreviewMask(mask, width, height)
        updateStatus("Selection ready — ▶ Polygon to keep it, or scribble more")
    }

    override fun onSegmentError(requestId: Int, message: String) {
        if (requestId != latestRequest) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus("Segmentation failed — see toast")
    }

    private fun convertMaskToPolygon() {
        val bmp = bitmap
        val mask = currentMask
        if (bmp == null || mask == null) {
            updateStatus("Draw an Add/Remove scribble first")
            return
        }
        // Mask is bitmap-sized; polygons must be original-image pixels.
        val traceW = bmp.width
        val traceH = bmp.height
        val pts = MaskToPolygon.trace(mask, traceW, traceH)
        if (pts.size < 3) {
            updateStatus("Selection too small — scribble a larger area")
            return
        }
        val sx = origW.toFloat() / traceW
        val sy = origH.toFloat() / traceH
        val origPts = pts.map { Pt(it.x * sx, it.y * sy) }
        promptLabel(lastLabel) { label ->
            pushUndo()
            shapes.add(Shape(label, origPts.toMutableList()))
            lastLabel = label
            view.shapes = shapes
            markDirty()
            // Leave Magic Touch with a clean slate: the polygon is the result.
            view.clearMagic()
            currentMask = null
            setMode(AnnotationView.Mode.EDIT)
            view.selected = shapes.size - 1
            updateStatus("Created \"$label\" from Magic Touch — drag handles to refine")
        }
    }

    // ── persistence / navigation ────────────────────────────────────────────

    private fun save() {
        val err = ProjectStore.save(
            context = this,
            project = project,
            shapes = shapes,
            imageWidth = origW,
            imageHeight = origH,
            withImageData = true,
        )
        if (err == null) {
            dirty = false
            updateTitle()
            Toast.makeText(this, "Saved ${project.name}.json (LabelMe format)", Toast.LENGTH_SHORT).show()
            updateStatus("Saved — image + JSON side by side")
        } else {
            Toast.makeText(this, "Save failed: $err", Toast.LENGTH_LONG).show()
        }
    }

    private fun attemptBack() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save the annotation before leaving?")
            .setPositiveButton("Save & exit") { _, _ -> save(); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    override fun onBackPressed() {
        attemptBack()
    }

    private fun markDirty() {
        dirty = true
        updateTitle()
    }

    private fun updateTitle() {
        findViewById<TextView>(R.id.titleText).text =
            project.name + if (dirty) " •" else ""
        btnSave.alpha = if (dirty) 1f else 0.55f
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }

    override fun onDestroy() {
        super.onDestroy()
        segmenter.close()
    }
}
