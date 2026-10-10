package com.testplaybyte.loom.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the bounded undo/redo stack (docs/04 §8). */
class UndoStackTest {

    @Test
    fun commitUndoRedoCycle() {
        val h = UndoStack<Int>(depth = 50)
        h.reset(0)
        h.commit { it + 1 }
        h.commit { it + 1 }
        assertEquals(2, h.present)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)

        h.undo()
        assertEquals(1, h.present)
        assertTrue(h.canRedo)
        h.undo()
        assertEquals(0, h.present)
        assertFalse(h.canUndo)

        h.redo()
        assertEquals(1, h.present)
        h.redo()
        assertEquals(2, h.present)
        assertFalse(h.canRedo)
    }

    @Test
    fun commitClearsRedoBranch() {
        val h = UndoStack<Int>(50)
        h.reset(0)
        h.commit { 1 }
        h.commit { 2 }
        h.undo() // back to 1, redo available
        assertTrue(h.canRedo)
        h.commit { 99 }
        assertFalse("commit must clear the redo branch", h.canRedo)
        assertEquals(99, h.present)
    }

    @Test
    fun depthBoundDropsOldestEntries() {
        val h = UndoStack<Int>(depth = 3)
        h.reset(0)
        for (i in 1..6) h.commit { i }
        assertEquals(6, h.present)
        // only 3 undo steps may remain
        h.undo(); h.undo(); h.undo()
        assertEquals(3, h.present)
        assertFalse(h.canUndo)
    }

    @Test
    fun resetClearsBothStacksWithoutRecording() {
        val h = UndoStack<Int>(50)
        h.reset(0)
        h.commit { 5 }
        h.reset(42)
        assertEquals(42, h.present)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun commitBeforeResetIsIgnored() {
        val h = UndoStack<Int>(50)
        h.commit { 1 } // no present yet
        assertEquals(null, h.present)
        assertFalse(h.canUndo)
    }
}
