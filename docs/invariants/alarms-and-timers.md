# Alarms and timers

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Alarms and timers

→ ADR 0010. **No server involvement, by design** — an alarm's instant is known in advance, so local arming
rings offline/dozing/app-killed. The pause cue needs the server only because its *timing* depends on
cross-device presence.

- The **days** are part of the alarm and are synced. An empty set never rings and is not the default.
- The phone arms its own OS exact alarm (soonest only; the receiver arms the next). The desktop **rings off
  the now-line** via an ordinary boundary sweep — it cannot arm what it isn't running for.
- The sweep **self-delays to the next ring**, de-dupes on **(id, instant)**, and has no screen-active gate.
- The boundary is `LocalDateTime(day, hh:mm).toInstant(tz)` — **not** `startOfDay + minutes`, which skews on
  DST days.
- Every ring is drawn on the calendar as an inert zero-duration marker, projected over the displayed span
  only.
- The tone is synthesized in commonMain (`AlarmTone.loopPcm()`, deterministic) so every device rings
  identically with no loadable resource. Android falls back to the system alarm ringtone if the PCM track
  fails — an alarm must never fail silently. The desktop uses its own thread, never the voice-cue worker.

### A timer is an alarm at an ABSOLUTE instant, and that is the whole difference

The Alarms window's second section (`SchedulerState.timers`, `TimerEntry`, `TimerDomain`). An alarm's due
instant is derived from the local calendar per ringing day; a timer's is **stored** — one instant, fixed when
it was started. Everything after "when is it due" is the alarms' machinery **unchanged**: do not grow a second
arming loop, a second sweep, a second ring path or a second notification funnel.

- **One OS slot ⇒ one arming loop and one sweep.** `AlarmClockScheduler` arms exactly one alarm under a fixed
  request code, so `launchAlarmArming` combines both lists and arms the **soonest of the two**, and
  `launchAlarmSweep` merges both crossing streams (`ringCrossingsBetween`) in boundary order. A second loop
  would not add a ring — it would overwrite the first's. Ids are disjoint (`alarm-{n}` / `timer-{n}`), so the
  sweep's `(id, instant)` de-dupe key cannot collide.
- **`ArmedAlarm.timer` is the one distinguishing bit, and it TRAVELS with the armed ring** — into the phone's
  OS intent included — never inferred from the id. It decides two things and nothing else: reset-vs-disarm,
  and whether the notification is titled *Timer* or *Alarm*.
- **`endsAtMillis` is authoritative; the remaining time is DERIVED.** The instant cannot be recomputed from
  anything else, so it is persisted **and synced** — which is what makes "it rings on every device of the
  account" true of a timer started on the desktop. The countdown is `endsAtMillis` minus the now-line
  (`remainingAtMillis`), so a running timer writes nothing and can never move the fingerprint on a tick.
- **Three states, two nullable fields, AT MOST ONE non-null** (running / paused / idle). Both are synced, so a
  per-field merge — or an older payload — can forge a row holding both; **`TimerDomain.healed` is the single
  place that invariant is applied**, from `decode`, from `SnapshotMerge` and from the reducer.
- **No on/off switch and no repeat switch.** A timer that is not running is already not due (an idle row is not
  a silenced one), and a timer is a one-off by nature: having rung it **resets** to its full duration. A
  one-off *alarm* disarms itself instead precisely because it has a switch to leave off.
- **Editing a row's settings must not disturb the instant it is due at, and a countdown edit must not touch
  the settings.** One rule, said both ways. `SetTimers` carries the settings; the run state moves only through
  `StartTimer` / `PauseTimer` / `ResetTimer` / `SetTimerCountdownField` / `NudgeTimerRemaining`, which take
  `nowMillis` as an argument so the reducer stays pure — and the window's local row copy deliberately holds no
  run state.
- **THE COUNTDOWN IS THREE INPUT FIELDS, AND AN EDIT IS A SHIFT BY THAT COMPONENT'S OWN UNIT** — never a
  rewrite of the countdown (`TimerDomain.withCountdownField`, the one rule behind `SetTimerCountdownField`).
  That is the whole of why **the finer components carry on reading down through the edit**: setting the hours
  moves the due instant by `(value − hours) × 1 h`, so the minutes and seconds underneath do not so much as
  jump; setting the minutes leaves the seconds running. "Make it 2 hours" and "restart it at 2 hours" are
  different answers and only the first is the one asked for. Each keystroke is measured against the **live**
  value, which is what makes typing `12` into the minutes (committing `1`, then `12`) land on 12 and not 13.
- **`SECONDS` is the ONE edit that stops it, and that is not an inconsistency — it is the reason the ± buttons
  exist.** The seconds are the digit that is itself reading down, so a value typed into a running timer would
  be consumed by the very next tick; there is no way to *set* it while it moves. So that edit **pauses** the
  row (Pause becomes Resume) and snaps the countdown to the whole second typed, which is what makes it stick.
  Only a **running** row is stopped by it — a paused or idle one has no countdown to stop and simply banks the
  snapped value, through the same primitive as every other write.
  `NudgeTimerRemaining` — `−10s / −5s / −1s / +1s / +5s / +10s`, `TimerDomain.nudged` — is how the seconds move
  **without** stopping, and it is the only reason both exist. Do not make the seconds field silently
  non-stopping (the value would not stick) and do not drop the buttons (the seconds would be unreachable while
  running).
- **Each write goes through `withRemaining`, in the state's OWN currency.** A **running** row's time left is
  `endsAtMillis`, so it moves and the row **stays running**; a **paused** row's is the banked `remainingMillis`,
  so that is rewritten and the row **stays paused**. Neither ever writes the other's field, which is what keeps
  the three-state invariant true without `healed` catching it.
- **An IDLE row's countdown is editable too, and editing it makes the row PAUSED — which is why the button
  then reads *Resume*.** Setting up how long this run is to be before pressing anything is the ordinary way to
  use a timer, and a countdown dialled in but not started *is* a held one: there is no fourth state to invent
  for it, and *Start* would be claiming the duration is what runs when it is not. The ± buttons work there for
  the same reason. **The two numbers stay one each**: `durationSeconds` is the *setting* the Duration field
  beside the countdown edits, it is what `reset` returns to and what a start from a genuinely idle row takes,
  and no countdown edit ever writes it — two fields writing one number by two routes is the drift this
  codebase keeps deleting. The one exception is an idle row retyped as the number it was already showing: it
  banks nothing and stays idle, so a value retyped as it was never turns *Start* into *Resume*. A **paused**
  row is not normalised back the other way — nudged onto its duration exactly it stays held, because a paused
  row is always written in its own currency.
- **The row holds ONE DRAFT, naming the field it belongs to** (`draft`, seeded on focus and dropped by
  `onFocusChanged` — only if it is still that field's, since Compose may report the gain before the loss). One,
  because only one field can hold the focus; and a draft at all because the live countdown changes four times a
  second, so a field bound straight to it cannot be typed into — every tick overwrites the keystroke. **The
  fields NOT holding the draft go on reading down**, which is what "editing the hours does not stop the minutes
  and seconds" looks like on screen. Display-only Compose state, like the poll below it; each keystroke that
  parses commits, one that does not shows the error state, so a half-typed value never reaches the state.
  Nothing downstream needs a change: `launchAlarmArming` already re-runs on every `state.timers` change.
- **The countdown's clock is the window's own**: the engine's now-line ticks once per 30 s production tick, so
  `AlarmWindow` polls `clock.nowMillis()` itself every 250 ms — **only while it is open and something is
  running**. Display-only Compose state, like the calendar's zoom. The transitions dispatch the clock's
  instant, not the quantized display now-line.
- **A running timer draws the SAME marker an alarm does**, on the same path — `CalendarRecord.alarm` is "this
  is a ring", and `CalendarRecord.timer` beside it is the one bit that says which sort, exactly as
  `ArmedAlarm.timer` does for an armed ring. It decides the icon (⏳ / ⏰) and nothing else; never fork the
  marker, the stacking sweep or the block exclusions on it.
  - **A timer marks the calendar at most ONCE, and only while it is running.** Its instant is stored, not
    derived per ringing day, so `TimerDomain.occurrencesInWindow` is a filter and not a walk; an idle or
    paused row has no instant, and a ring **resets** the row, so nothing is left behind afterwards. That is
    the whole of the difference — an alarm is a fact about the user's week, a timer exists between a start
    and a ring, and the calendar shows it for exactly that long.
  - The label falls back to the timer's **duration** where an alarm's falls back to its time of day — the
    thing each one is. `TimerDomain.formatDuration` / `formatCountdown` are that spelling, and they are the
    Alarms window's own: the window delegates to them so the two readouts cannot disagree.

### Both lists are Undo/Redo history, and the run state is not

→ `docs/invariants/task-tree.md` for the four-stack architecture the units live on.

- **Everything the user does to either LIST is one Main History Unit** — a row added, a row struck off with
  the bin, and every settings field of both sections (`AlarmsDelta` / `TimersDelta`, committed by
  `reduceSetAlarms` / `reduceSetTimers`). Ctrl+Z, therefore, and never Alt+←/→: that pair walks the
  *Selection* stack, and this window has no selection.
- **The five run-state writes are not units, and that is the rule.** `StartTimer` / `PauseTimer` /
  `ResetTimer` / `SetTimerCountdownField` / `NudgeTimerRemaining` write `endsAtMillis`, an ABSOLUTE instant
  measured against a now-line that keeps moving: a delta replayed later does not mean what it meant when it
  was recorded, and undoing a pause would restore an instant now in the past and ring the timer on the spot.
  A History Unit must be replayable. They also already carry their own inverses on the row (Pause ↔ Resume,
  Reset ↔ Start), which the bin does not.
- **Nothing the app authors itself is a unit**: the engine disarming a one-off that has rung
  (`SetAlarmEnabled` — the row's own switch is a *setting* and travels through `SetAlarms`, which is
  undoable), the reset after a ring, `healed`, a peer's pull. A machine-authored unit would sit on top of the
  Main stack and turn the next Ctrl+Z into "un-ring that".
- **A live-edited field is ONE unit per focus session.** The window pushes its whole list on every keystroke,
  so the unit carries a `Delta.coalesceKey` (`"{rowId}/{field}@{epoch}"`, minted when the field takes the
  focus) and `commitDelta` merges it onto the unit at the pointer when the keys match, keeping that one's
  `before` side. A structural change carries no key, so a switch flipped while a field still holds the focus
  can never be absorbed into that field's unit. The key is never persisted — a unit reloaded from the DB has
  closed its gesture.
- **The window's local row copies must re-seed from an outside change.** They hold unparsed text (`"7:"`), so
  they cannot simply follow the incoming list — every keystroke would be overwritten by the round-trip of its
  own push. They re-seed exactly when the incoming list is not the one this window last pushed; for the
  timers that comparison is the **settings only**, so a start never reformats a half-typed duration. Without
  it an undone deletion stays on screen and is pushed back at the next keystroke.
- **The window catches Ctrl+Z / Ctrl+Y itself and claims `AppWindow.Alarms`.** The chord lives on the tree's
  and the calendar's key handlers, and there is no app-level one — a keystroke aimed at a floating window
  reaches nobody. And `contentCategory` only lands on `Main` while the focus is on neither an Edit session nor
  the calendar, so the window has to own the app-wide focus or its units are skipped.
- **It claims the keyboard when it opens and RECLAIMS IT ON EVERY PRESS INSIDE IT** — the calendar's rule,
  through the same `raiseOnPress` that raises the window, and unconditionally focusable like the calendar is.
  Claiming it once is not enough, **and the bin is the proof**: a press on a row's bin destroys the row and
  with it whichever of its fields held the focus, so a window that only grabbed focus when it became the
  front one is left holding none — and the very Ctrl+Z that would undo the deletion reaches nobody. That is
  not hypothetical; it shipped, and the History rows showed the units committed with the pointer never
  moving. Do not gate either the `focusable()` or the reclaim on "is this the focused window".

---

