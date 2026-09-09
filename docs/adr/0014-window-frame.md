# 0014 — One window frame, and nothing that vanishes on a click

Active invariants: `docs/invariants/popups.md`. Dated log: `CHANGELOG.md`.

## Context

Until 2026-09-09 the app had **two sorts of pop-up**, and the sort was said to follow from the subject
rather than from a choice:

- **sort 1**, a window: one of it, stacks, stays until closed — the lateral-menu windows;
- **sort 2**, a transient pop-up: about ONE object (a task, a cell, a period, a category, a history row) and
  therefore **gone the moment anything else took focus**, discarding whatever was half-typed in it.

Sort 2 was the majority — the task edit window, the priority-weight table, relative priority, the period and
category windows, the calendar's entry / period / reminder editors, the deep-copy depth question, the app's
own notices. `TransientPopupHost` held the rule: one observer at the app root turned any press outside the
card's bounds into a dismissal, and `anyOpen` made the task tree go deaf while one was up.

Alongside that, each of the twelve lateral-menu windows carried its **own copy of the chrome**: a `Surface`,
a title `Row` doubling as the drag handle, and a single ✕. A window could be moved and nothing else — not
resized, not reduced, not maximized — and the fixed `requiredSize` of the taller ones (the calendar's
720×540) meant that on a short screen the head could be centred **above the top edge**, out of reach. The
calendar had grown a `clampOffsetY` of its own for exactly that, which no other window had.

## Decision

**One sort of window, one frame.**

1. **Nothing is dismissed by a press outside it.** Every window stays until it is closed. The single
   exception is a **menu** — a right-click contextual menu or a drop-down — which is not a window but a
   question about one object; `TransientPopupHost` was reduced to that role and renamed `TransientMenuHost`.
2. **`AppWindowFrame` is the whole of a window's chrome** (`ui/WindowFrame.kt`): the head that drags it, the
   five buttons (fill width, fill height, reduce, maximize, close), the double-click that maximizes, the
   three resizable edges, and the reduce bar along the bottom of the app.
3. **Maximize is not a state of its own**: it is `WindowFill.Both`, the two fill axes together. Un-filling
   restores the remembered normal geometry per axis.
4. **`WindowFrameState` is the only arithmetic**, and it is a plain observable class, so all of it — the
   centred-window `d/2` offset rule, the clamps, the fill/restore algebra — is unit-tested without a UI
   (`WindowFrameStateTest`).
5. **One stacking order for every window** (`WindowFrameHost.stackOrder`), raised when a window opens and on
   every press inside one. No caller pins a z: `Modifier.windowStackZ` reads that one order, and it goes on
   the window's outermost element.

## Why

- **The sort-2 rule cost more than it bought.** Its stated benefit was that "the user only ever means the
  one they just asked for" — but that is answered by the *single slot* each opener already has
  (`App`'s `editTaskId`, `weightWindowListId`, …), which makes opening the second window replace the first
  **by construction**. What outside-press dismissal added on top was only the losing half: a click aimed at
  the calendar behind a half-typed task edit threw the edit away, and ADR 0004's post-mortem is a whole
  section about a keystroke aimed at a pop-up dismissing it.
- **One funnel** (`CLAUDE.md`). Twelve copies of a title bar is twelve places for "what does maximized
  mean?" to drift. Putting the five buttons and the three edges behind one composable is what makes the
  answer the same everywhere — and it is what let the calendar's private `clampOffsetY` become
  `WindowFrameState.clampVertical`, which now protects every window rather than the one that noticed.
- **Resizing forces an explicit height.** A window whose height was its content's (`heightIn(max = …)` plus
  wrap) cannot be given a different height by dragging its bottom edge. So every window now declares a
  default width **and** height and puts its content in a `weight(1f)` slot. That change is what makes the
  bottom edge mean anything at all; it is not cosmetic.

### The stacking order came second (2026-09-09)

The first cut of this decision left the z alone, and that is where the sort-2 rule survived it: `App` kept a
stacking order of the twelve lateral-menu windows, and **every per-object window sat above the whole of it
on a fixed `zIndex(100f)`**. That was right for a window that left on the next press — it was on screen only
while it was the thing being answered, so "always on top" and "always the focused one" were the same
sentence. For a window that stays they come apart: open the priority-weight table, then press in the
Categories window, and the table goes on standing over it. Reported as an anomaly the same day.

So the order moved into `WindowFrameHost`, which already saw both events that decide it (a window opening,
a press landing inside one) and already answered the *other* two questions about the set of open windows.
Two details are the whole of the implementation:

- **`zIndex` orders a node among its own siblings**, so the z has to go on a window's **outermost** element.
  `TransientPopupLayer` and the `Box` that pairs a window with its companion window are full-screen wrappers
  with no z of their own — invisible lids that confined whatever the frame inside them said. The layer now
  takes the frame's id for that reason alone.
- **A window not yet in the stack reads as the top**, not the bottom: `register` runs after the composition
  that first draws the window, and the other answer flashes a newly opened window under its neighbours.

## Rejected

- **Keeping sort 2 for "notices only".** A notice with no timer and no scrim that leaves on the next press
  can be gone before it has been read — the worst case of the rule, not the best. `MessagePopup` is now an
  ordinary window with an OK button, and it is the one window that cannot be *reduced*: a notice filed in
  the bottom bar is a notice nobody reads.
- **Keeping the keyboard rule open-based.** `anyOpen → the tree is deaf` was correct while a pop-up left on
  the next press. With windows that stay, it would hold the keyboard for as long as the window stood there
  and the tree could never be typed in again without closing it. The rule now follows the **focus**
  (`WindowFrameHost.keyboardClaimed`): a window that answers keystrokes takes the focus when it opens — the
  press that opened it landed in the tree, so nothing else would hand it over — and gives it back on the
  first press in the tree.
- **Not composing a reduced window.** It is the obvious implementation and it throws away everything typed
  into the window, which is precisely what *reduce* promises not to do. A reduced window is measured and
  **not placed** (`Modifier.unplaced`), so it neither draws nor takes a press while keeping its state.
- **A separate tap detector for the head's double-click.** Two `pointerInput`s on one head race for the
  press and whichever wins decides whether the other ever sees the gesture. The double-click is detected
  inside the drag detector, only once the touch slop has been awaited and **not** crossed — so a slow drag
  can never be read as a double-click.
- **Persisting reduced / maximized.** The `window_placement` table already had `width`/`height` columns
  (added "for forward-compatibility with resizable windows"), so sizes persist with no migration. Reduced
  and filled would need a schema change, and they are facts about the session rather than about the account,
  so they are deliberately session-scoped.
- **Keeping the stack in `App` and pushing the per-object windows into it.** It works, and it puts each
  window's id in two places — the window's own `rememberWindowFrameState`, and the `zIndex` call at its call
  site in `App` — where a typo silently means "never raises". The host already held the registry keyed by
  exactly that id.
- **Re-ordering `WindowFrameHost.entries` on a raise instead of keeping a second list.** The reduce bar
  reads `entries`, and it lists windows in the order they were *opened*; a chip that jumps along the bar
  because its window was raised is a worse bug than the one being fixed. Two orders answer two different
  questions.
- **Reserving the reduce bar's height across the whole app.** The bar is asked to sit *over* the lateral
  menu, so it is an overlay at the app root; only the **content area** (where the windows live) is inset by
  its height, which is what keeps a maximized window from being hidden behind it.

## Post-mortem to avoid re-making

A rule that was *implied* by the old model outlives it silently. Outside-press dismissal was deleted in one
place and the fixed top layer it justified was left standing in another, three files away, reading as an
ordinary piece of layout. When a behaviour is removed, the thing to search for is not its name but every
constant that only made sense while it held.

The `heightIn(max = …)` caps that every list window carried are the other trap: they look harmless beside a
`weight(1f)`, and a window that keeps one silently stops following the height the user dragged it to. If a
window's content does not grow with its frame, look for the cap before looking at the frame.
