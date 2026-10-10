package com.testplaybyte.loom.domain.history

/**
 * Bounded undo/redo over an immutable value — exact port of the
 * prototype's `use-history.ts` semantics (docs/04 §8):
 *
 *  · [reset] sets a fresh present WITHOUT recording history (image switch,
 *    initial load);
 *  · [commit] pushes the previous present onto the undo stack (bounded by
 *    `depth`, oldest dropped) and clears redo — one completed gesture =
 *    one commit = one undo unit;
 *  · [undo]/[redo] move between the stacks symmetrically.
 *
 * Pure and synchronous: the UI layer observes `present` via state that it
 * derives itself (Compose snapshots) — this class owns only the stacks.
 */
class UndoStack<T>(var depth: Int) {

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    var present: T? = null
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Set a fresh value without recording history. */
    fun reset(value: T) {
        undoStack.clear()
        redoStack.clear()
        present = value
    }

    /** Apply [updater] and record the previous value as one undo unit. */
    fun commit(updater: (T) -> T) {
        val prev = present ?: return
        undoStack.addLast(prev)
        val cap = maxOf(2, depth)
        while (undoStack.size > cap) undoStack.removeFirst()
        redoStack.clear()
        present = updater(prev)
    }

    fun undo() {
        val prev = present ?: return
        val last = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(prev)
        present = last
    }

    fun redo() {
        val prev = present ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(prev)
        val cap = maxOf(2, depth)
        while (undoStack.size > cap) undoStack.removeFirst()
        present = next
    }
}
