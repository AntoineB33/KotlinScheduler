package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 the priority-weight table's **default row**: the row under the add row that states what a task
 * ARRIVING in this table is given — whether it arrives by being named in the task tree or by being added to
 * the table itself as an optional row.
 *
 * By default it is 1 in the first column and 0 in the others (the built-in weight defaults, so an account
 * that never touches the row behaves exactly as it did before the row existed), it follows every column
 * operation like any other row of the table, and it states nothing about the priorities that are already
 * there — so it moves no share and re-plans nothing.
 */
class PriorityWeightDefaultRowTest {

    /** `root ─ Book ─ (Chapter, Other)`. */
    private class Fixture(
        val state: SchedulerState,
        val root: CellListId,
        val bookList: CellListId,
        val chapter: TaskId,
    )

    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val rootCells = { s.lists[s.rootListId]!!.cellIds }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(rootCells()[0], "Book"))
        val book = s.cells[rootCells()[0]]!!.taskId!!
        val bookList = s.tasks[book]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[bookList]!!.cellIds[0], "Chapter"))
        val chapter = s.cells[s.lists[bookList]!!.cellIds[0]]!!.taskId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[bookList]!!.cellIds[1], "Other"))
        return Fixture(s, s.rootListId, bookList, chapter)
    }

    /** The last cell of [listId] is its trailing placeholder; name it and read the row it arrived on. */
    private fun nameANewTask(s: SchedulerState, listId: CellListId, title: String): List<Double> {
        val placeholder = s.lists[listId]!!.cellIds.last()
        val next = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(placeholder, title))
        return next.cells[placeholder]!!.priorityWeights
    }

    // ----- what the row says by default -----------------------------------------------------------

    @Test
    fun the_default_row_is_one_in_the_first_column_and_zero_in_the_others() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(f.bookList))
        assertEquals(listOf(1.0, 0.0, 0.0), s.lists[f.bookList]!!.defaultWeights)
        // …which is exactly what a task used to be given, so an untouched account is unchanged.
        assertEquals(
            List(3) { SchedulerDomain.defaultWeightAt(it) },
            s.lists[f.bookList]!!.defaultWeights,
        )
    }

    // ----- a task ARRIVES on it, however it arrives ------------------------------------------------

    @Test
    fun a_task_named_in_the_tree_arrives_on_the_default_row() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 4.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 1, 7.0))

        assertEquals(listOf(4.0, 7.0), nameANewTask(s, f.bookList, "Third"))
    }

    @Test
    fun the_row_is_read_when_the_task_is_NAMED_not_when_its_placeholder_was_minted() {
        // The placeholder sits at the bottom of the list for as long as the list exists, so seeding it at
        // creation would hand a new task whatever the row said before the user edited it.
        val f = fixture()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 5.0))
        assertEquals(listOf(5.0), nameANewTask(s, f.bookList, "Third"))
    }

    @Test
    fun renaming_a_task_that_is_already_in_the_table_leaves_its_row_alone() {
        val f = fixture()
        val cell = f.state.lists[f.bookList]!!.cellIds.first()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityWeight(cell, 0, 9.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 5.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Chapter one"))
        // It is not arriving — it is already a row of this table, with a weight the user set.
        assertEquals(9.0, s.cells[cell]!!.priorityWeights[0])
    }

    @Test
    fun an_optional_row_added_to_the_table_arrives_on_the_default_row_too() {
        val f = fixture()
        // "Chapter" has an occurrence under Book, so the root table can state its share of the root list.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.root, 0, 3.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeightTableRow(f.root, taskId = f.chapter))
        assertEquals(listOf(3.0), s.lists[f.root]!!.optionalTaskValues[f.chapter])
    }

    // ----- it is a row of the table, so the columns carry it ---------------------------------------

    @Test
    fun an_added_column_is_zero_in_the_default_row_and_a_deleted_one_leaves_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 2.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(f.bookList, 0))
        assertEquals(listOf(0.0, 2.0), s.lists[f.bookList]!!.defaultWeights)

        s = SchedulerReducer.reduce(s, SchedulerIntent.DeletePriorityColumn(f.bookList, 0))
        assertEquals(listOf(2.0), s.lists[f.bookList]!!.defaultWeights)
    }

    @Test
    fun moving_a_column_moves_the_default_rows_value_with_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 1, 6.0))
        assertEquals(listOf(1.0, 6.0), s.lists[f.bookList]!!.defaultWeights)

        s = SchedulerReducer.reduce(s, SchedulerIntent.MovePriorityColumn(f.bookList, 1, 0))
        assertEquals(listOf(6.0, 1.0), s.lists[f.bookList]!!.defaultWeights)
    }

    @Test
    fun resetting_a_column_resets_the_default_row_with_the_header_and_the_cells() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 8.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ResetPriorityColumn(f.bookList, 0))
        assertEquals(listOf(1.0), s.lists[f.bookList]!!.defaultWeights)
    }

    // ----- what it must NOT do ---------------------------------------------------------------------

    @Test
    fun editing_the_default_row_moves_no_share_and_re_plans_nothing() {
        val f = fixture()
        val before = SchedulerDomain.absoluteTaskPriorities(f.state)
        val signature = SchedulerDomain.schedulingSignature(f.state)

        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 12.0))

        assertEquals(before, SchedulerDomain.absoluteTaskPriorities(s))
        assertEquals(signature, SchedulerDomain.schedulingSignature(s))
    }

    @Test
    fun editing_the_default_row_is_one_undoable_unit_and_a_no_op_is_none() {
        val f = fixture()
        val units = f.state.histories.forCategory(HistoryCategory.Main).units.size

        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 3.0))
        assertEquals(units + 1, s.histories.forCategory(HistoryCategory.Main).units.size)
        assertEquals("Default weight", s.histories.forCategory(HistoryCategory.Main).units.last().delta.label)

        // The field commits every keystroke, so typing the value it already holds must record nothing.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 3.0))
        assertEquals(units + 1, s.histories.forCategory(HistoryCategory.Main).units.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(listOf(1.0), s.lists[f.bookList]!!.defaultWeights)
    }

    @Test
    fun cancel_puts_the_default_row_back_with_the_rest_of_the_table() {
        val f = fixture()
        val opened = f.state.lists[f.bookList]!!
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 0, 3.0))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.RestorePriorityWeights(
                listId = f.bookList,
                weightColumns = opened.weightColumns,
                cellWeights = opened.cellIds.associateWith { f.state.cells[it]!!.priorityWeights },
                defaultWeights = opened.defaultWeights,
            ),
        )
        assertEquals(listOf(1.0), s.lists[f.bookList]!!.defaultWeights)
    }

    // ----- persistence -----------------------------------------------------------------------------

    @Test
    fun the_default_row_round_trips_through_the_codec() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddPriorityColumn(f.bookList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityDefaultWeight(f.bookList, 1, 5.0))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(listOf(1.0, 5.0), decoded.lists[f.bookList]!!.defaultWeights)
    }

    @Test
    fun a_payload_written_before_the_default_row_existed_loads_with_the_built_in_one() {
        // Persisted-DB rule: an on-disk DB from a build with no default row must still load, and a task
        // added afterwards must be given exactly what that build gave it.
        val oldJson =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"],"weightColumns":[1.0,0.5]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(oldJson)
        assertNotNull(decoded)
        val list = decoded.lists[CellListId("L")]!!
        assertEquals(listOf(1.0), list.defaultWeights)
        // Read to the list's two columns it is the built-in row, so nothing about the account changed.
        assertEquals(listOf(1.0, 0.0), SchedulerDomain.defaultWeightRow(list))
        assertTrue(decoded.cells.isNotEmpty())
    }
}
