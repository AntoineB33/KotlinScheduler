package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.state.HistorySource
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.FilteredHistoryEntry
import org.example.project.ui.HistoryFilterConfig
import org.example.project.ui.filteredHistoryUnits

/**
 * PRD §6/§9: the History window's **scheduler engine** source.
 *
 * A re-plan is not a History Unit — PRD §9 is explicit that a schedule is derived from the current state, so
 * nothing undoes it — but it is the app deciding something, and the one thing about it the user cannot read
 * anywhere else is the **set of rules** the scheduler answered from. These pin that the two plan reductions
 * report a run, that the run carries the rules, and that the rules say what `side-dev/README.md` says a rule
 * is (a share, a minimum, a resilience).
 */
class SchedulerRunLogTest {
    private val previousSink = SchedulerReducer.recordSchedulerRun
    private val runs = mutableListOf<SchedulerRunEntry>()

    @AfterTest
    fun restore() {
        SchedulerReducer.recordSchedulerRun = previousSink
    }

    private fun collect() {
        SchedulerReducer.recordSchedulerRun = { runs.add(it) }
    }

    @Test
    fun a_re_plan_reports_the_rules_it_ran() {
        collect()
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Deep work"))
        runs.clear()

        SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(0L))

        val run = runs.single()
        assertEquals(SchedulerRunEntry.Kind.Replan, run.kind)
        assertTrue(
            run.rules.any { it.contains("Deep work") },
            "the run must carry the rule of every schedulable task: ${run.rules}",
        )
    }

    @Test
    fun a_horizon_extension_is_reported_as_the_other_event() {
        // PRD §9: growing the horizon is NOT a change to the scheduling rules, and the row has to say so —
        // otherwise a user reading the log cannot tell a re-plan from a materialization of the same plan.
        collect()
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Deep work"))
        runs.clear()

        SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(0L))

        assertEquals(SchedulerRunEntry.Kind.Extension, runs.single().kind)
    }

    @Test
    fun a_rule_spells_the_share_the_minimum_and_the_resilience() {
        // `side-dev/README.md`: resilience is "the ONE thing that says where a task may run and at what
        // share", so a rule that omitted it would not be the rule the scheduler read.
        val plain =
            SchedulerDomain.describePlanRule(
                PlanTask(id = TaskId("task/1"), priority = 0.5, minimumMillis = 45 * 60_000L),
                title = "Deep work",
            )
        assertTrue(plain.contains("Deep work"), plain)
        assertTrue(plain.contains("50.0%"), plain)
        assertTrue(plain.contains("45 min"), plain)
        assertTrue(plain.contains("on screen only"), plain)

        val resilient =
            SchedulerDomain.describePlanRule(
                PlanTask(
                    id = TaskId("task/2"),
                    priority = 0.25,
                    minimumMillis = 0L,
                    resilience = mapOf(PeriodKinds.NO_SCREEN to 1.0),
                ),
                title = "Walk",
            )
        assertTrue(resilient.contains(PeriodKinds.NO_SCREEN), resilient)
        assertTrue(resilient.contains("100.0%"), resilient)
    }

    @Test
    fun the_runs_reach_the_history_window_under_the_scheduler_engine_source() {
        val entry =
            SchedulerRunEntry(
                timeMillis = 4_000,
                kind = SchedulerRunEntry.Kind.Replan,
                horizonMillis = 9_000,
                panelCount = 2,
                rules = listOf("Deep work - priority 50.0%, minimum 45 min, resilience: on screen only"),
            )
        val rows =
            filteredHistoryUnits(
                SchedulerState.empty().histories,
                HistoryFilterConfig(filterBySource = true, source = HistorySource.SchedulerEngine),
                schedulerRuns = listOf(entry),
            )
        assertEquals(entry, (rows.single() as FilteredHistoryEntry.SchedulerRun).entry)

        // ...and only under that source: the window field lists History Units, and a run is not one.
        assertTrue(
            filteredHistoryUnits(
                SchedulerState.empty().histories,
                HistoryFilterConfig(),
                schedulerRuns = listOf(entry),
            ).isEmpty(),
        )
    }
}
