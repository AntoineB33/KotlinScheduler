package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.example.project.ui.SheetColors
import org.example.project.ui.TaskCellOutline
import org.example.project.ui.borderColor
import org.example.project.ui.borderWidth
import org.example.project.ui.taskCellOutline

/**
 * PRD §3/§4: a task cell can be in three states — in the selection, the **main** selection, and **Edit
 * Mode** — and each has its own outline, because that outline is the only place either is said (the cell
 * keeps its task colour as its background whatever it is doing).
 *
 * The states are NESTED in the state itself — the edited cell is also the main selection, which is also in
 * the selection range — so what is tested here is the ranking that turns three overlapping flags into one
 * drawing, and that the four drawings are actually different from one another.
 */
class TaskCellOutlineTest {

    @Test
    fun `an unselected cell wears the plain grid line`() {
        val outline = taskCellOutline(isEditing = false, isMainSelection = false, isInSelectionRange = false)
        assertEquals(TaskCellOutline.None, outline)
        assertEquals(SheetColors.grid, outline.borderColor)
    }

    @Test
    fun `a cell of the selection that is not main wears the thin active border`() {
        val outline = taskCellOutline(isEditing = false, isMainSelection = false, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Selected, outline)
        assertEquals(SheetColors.activeBorder, outline.borderColor)
    }

    @Test
    fun `the main selection wears the thick active border`() {
        // Main is always in the selection range too: the ranking, not the caller, is what keeps the two apart.
        val outline = taskCellOutline(isEditing = false, isMainSelection = true, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Main, outline)
        assertEquals(SheetColors.activeBorder, outline.borderColor)
    }

    @Test
    fun `edit mode wins over the main selection and wears its own colour`() {
        // The edited cell is the main selection as well, so Edit Mode has to be read FIRST — this is the
        // whole of what changed when the two stopped sharing one outline.
        val outline = taskCellOutline(isEditing = true, isMainSelection = true, isInSelectionRange = true)
        assertEquals(TaskCellOutline.Editing, outline)
        assertEquals(SheetColors.editBorder, outline.borderColor)
    }

    @Test
    fun `the four outlines are pairwise distinguishable`() {
        // A state the user cannot tell from its neighbour is the same as not drawing it at all.
        val drawings = TaskCellOutline.entries.map { it to (it.borderWidth to it.borderColor) }
        for ((a, drawingA) in drawings) {
            for ((b, drawingB) in drawings) {
                if (a == b) continue
                assertNotEquals(drawingA, drawingB, "$a and $b are drawn identically")
            }
        }
    }
}
