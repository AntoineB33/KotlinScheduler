package org.example.project

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.ui.undoRedoIntentFor

/**
 * PRD §5: what an undo/redo chord means — the ONE reading of it, pinned.
 *
 * It exists because the rule was spelled out three times (the task tree, the calendar, the Alarms window) and
 * none of the three looked at Shift, so **Ctrl+Shift+Z — redo in every other application — fell into the
 * `Ctrl + Z` branch and undid a third time**: delete a timer, Ctrl+Z (it comes back), Ctrl+Z (its title
 * goes), then Ctrl+Shift+Z, which should have brought the title back and instead deleted the timer again.
 */
class UndoRedoChordTest {

    private fun chord(
        key: Key,
        ctrlOrMeta: Boolean = true,
        shift: Boolean = false,
        keyDown: Boolean = true,
    ): SchedulerIntent? = undoRedoIntentFor(key, keyDown = keyDown, ctrlOrMeta = ctrlOrMeta, shift = shift)

    @Test
    fun ctrl_z_undoes() {
        assertEquals(SchedulerIntent.Undo, chord(Key.Z))
    }

    /** The anomaly: this is REDO, and must never reach Undo. */
    @Test
    fun ctrl_shift_z_redoes() {
        assertEquals(SchedulerIntent.Redo, chord(Key.Z, shift = true))
    }

    @Test
    fun ctrl_y_redoes() {
        assertEquals(SchedulerIntent.Redo, chord(Key.Y))
        // Shift changes nothing for the Y spelling: it is redo either way, never a second undo.
        assertEquals(SchedulerIntent.Redo, chord(Key.Y, shift = true))
    }

    @Test
    fun a_bare_key_is_not_a_chord() {
        assertNull(chord(Key.Z, ctrlOrMeta = false))
        assertNull(chord(Key.Z, ctrlOrMeta = false, shift = true))
        assertNull(chord(Key.Y, ctrlOrMeta = false))
        assertNull(chord(Key.A))
    }

    /** Key-up must not fire it a second time — the surfaces preview both edges of every stroke. */
    @Test
    fun only_the_key_down_edge_fires() {
        assertNull(chord(Key.Z, keyDown = false))
        assertNull(chord(Key.Z, shift = true, keyDown = false))
        assertNull(chord(Key.Y, keyDown = false))
    }
}
