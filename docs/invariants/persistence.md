# Persistence: history units and the store

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### Adding a History Unit is ONE INSERT

→ ADR 0007. A History Unit is **immutable once committed** — with one named exception below — and the only
things that happen to a category's list are an append, a redo branch discarding the tail, and the cap evicting
the head. Persisting it must cost what those are worth — one row — and every piece below exists because it did
not.

A unit records **where it was made**: `HistoryUnit.window`, stamped at commit from
`SchedulerReducer.activeWindow`, which `App.kt` feeds from its window stack (`bringWindowToFront`, plus the
content Box's raise-on-press for the tree). It is the History window's window drop-down and nothing else reads
it. Do not derive it from `HistoryCategory` (the Ctrl+Z stack: `Main` alone holds tree mutations, alarms,
timers, categories, relations and rebound chords) nor from `SchedulerState.focusedWindow` (the PRD §7 focus
target, which only five windows claim). NULL is legitimate and permanent on a unit written before 11.sqm.

The exception: a unit whose gesture is still open (`Delta.coalesceKey` — a field being typed into live, see
`docs/invariants/alarms-and-timers.md`) is **replaced at the pointer** by the next keystroke's unit rather than
appended after it. It costs the alignment below nothing special: the replacement's `(length, hash)` differ, so
the digest diff simply ends the matched run there and the row is rewritten — one delete plus one insert, which
is what the append it replaces would have cost anyway, and the list does not grow.

- **The `history_unit` row's key is a stable `seq`, NOT the unit's position.** It is allocated once, when the
  unit is first written, and never renumbered; the list order is the seq order and the dense index the rest
  of the app speaks in (`HistoryRow.ordinal`, what a `history_pointer` indexes) is derived back from it on
  load. Keying on the position is what forced the rewrite: **the cap evicts from the FRONT**, so one new unit
  renumbered all `MAX_HISTORY_UNITS` of them and no row could be reused.
- **A save diffs the DIGEST, never the deltas** (`selectHistoryDigests`, `HistoryDigest`). Identity is
  `(timeMillis, chronoId, debugTainted, length, hash)`; a wrong "different" costs one rewritten row, a wrong
  "same" would keep a stale delta, which is why all five must agree. `window` is deliberately NOT among them:
  it is stamped once at commit and can never differ between a stored row and the unit that matched it, so
  adding it would only have made carried-up rows (whose column is NULL) look different and rewrite the lot. Reading the deltas back to compare them
  would reload exactly the bytes this exists to stop writing.
- **`delta` is the LAST column a SAVE reads, and that is a rule, not a layout.** SQLite walks a record from
  the front and a delta of tens of KB lives in overflow pages, so a column read after it drags the whole
  chain in: the same digest scan costs 36 ms with `delta` before it and 3 ms with it after (54 MB history).
  Anything a save reads goes BEFORE `delta`. The one column allowed after it is `window` (11.sqm): no save
  reads it — a unit is immutable, so its window takes no part in the digest — and its only reader is the full
  load, which walks `delta` anyway. That is what let it be an `ALTER TABLE ADD COLUMN` instead of the table
  rebuild 10.sqm needed, i.e. instead of rewriting tens of MB of delta text on the release account's first
  launch. **A new column that a save WOULD read still has to be a rebuild.**
- **`SchedulerStateCodec` memoizes each unit's serialized delta on the unit** (`HistoryUnit.encodedDelta`,
  seeded on load from the row just read). Without it the store's thrift is pointless: `encodeSnapshot` runs
  on every save AND again for every `syncFingerprint`, so the history was re-serialized **twice per
  keystroke debounce** — 267 ms a time on a full 1000-unit stack — before the store was even called. The
  reducer carries the same unit OBJECT through every state copy, which is what makes the memo hold.
- **`encodeSnapshot` asks for the payload WITHOUT the histories** (`toPersisted(withHistories = false)`). It
  used to build them into `PersistedState` and then `.copy(histories = null)` them away.
- Measured, 1000 units / 54 MB: **~500–870 ms → ~25–40 ms** to write, **267 ms → ~2 ms** to encode.

---

