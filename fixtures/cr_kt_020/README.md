# CR-KT-020: task completion writes the PWA history point (+ todo `completedAt`, realized-delta `_gr`, `unlogToday`)

- `complete_history.json`: generated, do not edit (never edit after ACCEPTED, PROTOCOL P4). Regenerate with:
  `git -C <Opti> show afdae43:app.js > app.js` then `node gen_complete_history.js app.js > complete_history.json`.
  Run with TZ=UTC (the script also sets it): `dayStamp(new Date())` is local-time, and `unlogToday`'s day test must not vary per machine.
- The generator does not copy code by hand: it cuts `logHistory`, `completeTask`, `reverseGrant`, `unlogToday`,
  `uncompleteDaily`, `uncompleteTodo` and the helpers (`clamp`, `DIFF`, `valueDelta`, `completionReward`,
  `isDailyDueOn`, `isDailyDueToday`, `dayStamp`) out of Opti afdae43 `app.js` by text markers and runs them with
  `new Function`. Stubbed: `S.char` effects (`gainXp`, `takeDamage`), `buzz`, `floatFx`, `fxGain`, `bumpAvatar`,
  `save`, `render`, `toast`. `logEvent` is captured in its stored shape (`ts/uid/dev` first, then the event;
  `uid` is harness-stamped `fx20-<n>`). `Date` is frozen per step (`Date.now()`/`new Date()` = that step's ms);
  the HLC `now()` returns `hlc_base + 1` on the first call, `+2` on the next.
- Every `cases[]` row: `task_before`, `hlc_base`, `now_ms` (+ `now_ms_2` when the case un-ticks), `after_complete`,
  `events`, and for un-tick cases `after_uncomplete` / `events_uncomplete`. Task JSON is the whole object the PWA
  holds, including `_gr`, `_histCreated`, `doneAt`, `doneDay`.
- The 10 cases: fresh todo, fresh daily, merge onto today's cron-miss point (`_histCreated:false`), checklist
  snapshot (ids dropped on the point, kept on the event), 99-ceiling realized delta, paused daily (point still
  written, streak unchanged), un-tick pop (fresh point), un-tick keep (merged point: `completed:false`, value
  restored, reward dropped), todo un-tick, un-tick on the next UTC day.

## PWA behaviour pinned as-is (Kotlin mirrors it; changes come from the PWA side first)

- `uncompleteTodo` never deletes `completedAt` -> the analytics todo fallback (`!any && t.completedAt`,
  app.js:4428) still counts a reverted todo (`todo_uncomplete_keeps_point`).
- `unlogToday` only touches a point dated the current UTC day -> an un-tick after the UTC day rolls over leaves
  the old `completed:true` point and `_histCreated` in place (`daily_uncomplete_next_day_guard`).
- `_histCreated` and `_gr` are not stripped on save (`syncSubset()` copies whole tasks, sync.js:807-812) -> they
  ride state.json until the matching un-tick deletes `_histCreated`.

## Not in this fixture

- The `complete`/`uncomplete` EVENT payloads are captured for reference only; Kotlin event-field parity
  (reward/repeat/checklist/clawback) is a separate CR.
- Not covered: missing-`_gr` un-tick fallback (`reverseGrant` then subtracts `valueDelta(t.value)`), habit taps,
  cron, import.
