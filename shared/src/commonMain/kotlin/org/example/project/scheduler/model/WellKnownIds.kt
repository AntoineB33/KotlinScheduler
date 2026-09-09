package org.example.project.scheduler.model

/**
 * The four ids every task tree in the account is rooted at.
 *
 * There used to be **two** well-known tasks: a conceptual `root` holding a single child `main`, so that
 * sibling trees could one day hang beside `main` under the same root. Named task trees (PRD §6, the "All
 * task trees" window) are how the account actually holds several trees, so the extra level answered nothing
 * and was removed — the tree has ONE root, and it is called `root`. A payload written before that is
 * migrated on load (`SchedulerStateCodec`); `SchedulerDomain.withRoot` is the one definition of the shape.
 */
object WellKnownIds {
    /**
     * The tree's one inert **root task**, titled `root`. Its [ROOT_LIST] holds the tree's top-level cells,
     * so it stands for the whole tree: it is what an absolute priority percentage is a share *of*, what the
     * relative-priority window's `t_r` drop-down and the task-relations window mean by "root", and what a
     * Change Task menu's shortest path is named from.
     *
     * It is never selectable, never editable, never assignable to a cell and never scheduled.
     */
    val ROOT_TASK = TaskId("task/root")

    /**
     * [ROOT_TASK]'s child list — the tree's **top-level list**, and what
     * [org.example.project.scheduler.state.SchedulerState.rootListId] names on the live tree. Every walk
     * that asks "the tree's tasks" starts here.
     */
    val ROOT_LIST = CellListId("list/root")

    /**
     * PRD §2: the **root cell** — the single inert row the tree is drawn under. It points at [ROOT_TASK],
     * whose sub-list is [ROOT_LIST], so collapsing it collapses the tree.
     *
     * PRD §3: it is not selectable, which is what makes it inert — no click, no keyboard walk and no Edit
     * Mode reaches it. It is drawn as a small strip (the expand arrow alone, no title and no columns), and
     * it is the one unselectable cell that still opens a PRD §13 contextual menu: the entries about the
     * tree as a whole, "edit task" excepted, since the root has no task to edit.
     *
     * It is a real cell of the tree and not a synthetic header so that exactly one thing draws a task row,
     * one thing decides the visible order, and the expansion set answers for the root the same way it
     * answers for every other parent.
     *
     * The id carries no numeric suffix on purpose: `deriveNextCellCounter` reads the suffix of every cell
     * id, and a cell the counter must never re-mint has to be unreadable to it.
     */
    val ROOT_CELL = CellId("cell/root")

    /**
     * The list holding [ROOT_CELL] alone — one level **above** [ROOT_LIST], which is the whole of what tells
     * the two apart: [ROOT_LIST] is the list of the tree's top-level tasks, this is the list of the one row
     * they are drawn under. It is what [org.example.project.scheduler.domain.SchedulerDomain.displayRootListId]
     * answers with, and it is reached from [ROOT_LIST]'s own `parentCellId`.
     */
    val ROOT_CELL_LIST = CellListId("list/root-cell")
}
