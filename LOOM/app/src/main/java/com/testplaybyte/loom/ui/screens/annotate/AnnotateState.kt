package com.testplaybyte.loom.ui.screens.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.testplaybyte.loom.domain.history.UndoStack
import com.testplaybyte.loom.domain.model.Dot
import com.testplaybyte.loom.domain.model.ImageState
import com.testplaybyte.loom.domain.model.Pt

/** The six canvas tools (docs/03 §6 tool bar). */
enum class Tool { PAN, DOT_POS, DOT_NEG, BRUSH, EDIT, ERASER }

/** What is currently selected on the canvas (edit tool). */
sealed interface Selection {
    data class Vertex(val index: Int) : Selection
    data class DotSel(val id: String) : Selection
}

/** In-flight gesture visuals (never persisted; the commit writes them). */
sealed interface Live {
    data class VertexLive(val index: Int, val pos: Pt) : Live
    data class DotLive(val id: String, val pos: Pt) : Live
    data class BrushLive(val pts: List<Pt>) : Live
}

/** Camera: world → screen is `p * scale + t`. */
data class Cam(val scale: Float, val tx: Float, val ty: Float)

/**
 * AnnotateState — the working copy behind the canvas (docs/04 §8).
 *
 * Holds the ONE image's [ImageState] plus its bounded undo history
 * (settings.undoDepth), the active tool, the current selection, the
 * in-flight gesture visual, the camera and the transient UI flags
 * (sculpting, pulse, density draft). Every COMPLETED gesture goes through
 * [commit] = exactly one undo unit.
 *
 * Deliberately a plain class instantiated with `remember` in the screen —
 * the canvas needs cheap synchronous state, and persistence is the
 * screen's job (autosave debounce → repository).
 */
class AnnotateState(undoDepth: Int) {

    private val stack = UndoStack<ImageState>(undoDepth)

    var present by mutableStateOf(ImageState.empty(16))
        private set

    var tool by mutableStateOf(Tool.PAN)
    var selection by mutableStateOf<Selection?>(null)
    var live by mutableStateOf<Live?>(null)
    var cam by mutableStateOf(Cam(1f, 0f, 0f))

    /** True while a dot change is waiting on the 600ms re-sculpt. */
    var sculpting by mutableStateOf(false)

    /** Bumped when the mesh is rebuilt so vertex handles replay + selection drops. */
    var meshGen by mutableIntStateOf(0)

    /** Live density while the stepper is being dragged (remesh preview). */
    var densityDraft by mutableStateOf<Int?>(null)

    /** True while a canvas gesture is live (defers the sculpt commit). */
    var gestureActive by mutableStateOf(false)

    /** Visual haptic pulse flag (280ms). */
    var pulse by mutableStateOf(false)

    /** Zoom readout for the zoom chip (percent). */
    var zoomPct by mutableIntStateOf(100)

    /**
     * Camera requests from the chrome (zoom chip / fit button). The canvas
     * consumes and clears them — it owns the gesture engine that actually
     * moves the camera.
     */
    var pendingZoomFactor by mutableStateOf<Float?>(null)
    var pendingFit by mutableStateOf(false)

    fun requestZoom(factor: Float) {
        pendingZoomFactor = factor
    }

    fun requestFit() {
        pendingFit = true
    }

    val canUndo: Boolean get() = stack.canUndo
    val canRedo: Boolean get() = stack.canRedo

    /** Load a fresh image: reset history to the loaded state (no undo). */
    fun reset(state: ImageState) {
        stack.reset(state)
        present = state
        live = null
        selection = null
        densityDraft = null
        sculpting = false
    }

    /** One completed gesture = one undo unit. */
    fun commit(updater: (ImageState) -> ImageState) {
        stack.commit(updater)
        present = stack.present ?: return
    }

    fun undo() {
        stack.undo()
        present = stack.present ?: return
    }

    fun redo() {
        stack.redo()
        present = stack.present ?: return
    }

    // ── convenience read views ────────────────────────────────────────────

    val dots: List<Dot> get() = present.dots
    val mesh: List<Pt>? get() = present.mesh
    val strokes: List<com.testplaybyte.loom.domain.model.Stroke> get() = present.strokes

    /** Mesh with the in-flight vertex drag applied (render-only). */
    fun liveMesh(): List<Pt>? {
        val m = present.mesh ?: return null
        val l = live
        if (l !is Live.VertexLive) return m
        return m.mapIndexed { i, p -> if (i == l.index) l.pos else p }
    }

    /** Dots with the in-flight dot drag applied (render-only). */
    fun liveDots(): List<Dot> {
        val l = live
        if (l !is Live.DotLive) return present.dots
        val px = l.pos.x
        val py = l.pos.y
        return present.dots.map { if (it.id == l.id) it.copy(x = px, y = py) else it }
    }

    fun cameraScale(): Float = cam.scale
    fun setCamera(c: Cam) {
        cam = c
        zoomPct = Math.round(c.scale * 100f)
    }
}
