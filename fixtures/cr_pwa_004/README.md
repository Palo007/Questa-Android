# CR-PWA-004: un-tick retracts the whole completion record (PWA fix)

- `untick_fix.json`: generated, do not edit (never edit after ACCEPTED, PROTOCOL P4). Regenerate with:
  `git -C <Opti> show 338d430:app.js > app.js` then `node gen_untick_fix.js app.js > untick_fix.json`.
  Run with TZ=UTC (the script also sets it). `338d430` is the Opti commit that first contains the
  CR-PWA-004 fix (first commit on branch `fix/cr-pwa-004-untick`). Do not regenerate from a later
  app.js that changes `unlogToday`/`uncompleteTodo` again; if that commit is ever unavailable, any
  app.js whose `unlogToday` has no UTC-day test and whose `uncompleteTodo` deletes `completedAt`
  reproduces the same bytes.
- Byte-stability was verified at generation time: two independent runs produced the same sha256
  (recorded in the Opti evidence: `.cline\evidence\task-fixture-cr-pwa-004-untick-fix.txt`).
- The generator does not copy code by hand: it cuts `logHistory`, `completeTask`, `reverseGrant`,
  `unlogToday`, `uncompleteDaily`, `uncompleteTodo` and the helpers (`clamp`, `DIFF`, `valueDelta`,
  `completionReward`, `isDailyDueOn`, `isDailyDueToday`, `dayStamp`) out of Opti `app.js` at run time
  by text markers and runs them with `new Function`. Stubbed: `S.char` effects (`gainXp`,
  `takeDamage`), `buzz`, `floatFx`, `fxGain`, `bumpAvatar`, `save`, `render`, `toast`. `logEvent` is
  captured in its stored shape (`ts/uid/dev` first, then the event; `uid` is harness-stamped
  `fx20-<n>`). `Date` is frozen per step (`Date.now()`/`new Date()` = that step's ms); the HLC
  `now()` returns `hlc_base + 1` on the first call, `+2` on the next.
- Every `cases[]` row: `task_before`, `hlc_base`, `now_ms` (+ `now_ms_2` when the case un-ticks),
  `after_complete`, `events`, and for un-tick cases `after_uncomplete` / `events_uncomplete`. Task
  JSON is the whole object the PWA holds, including `_gr`, `_histCreated`, `doneAt`, `doneDay`.

## What changed vs CR-KT-020 `complete_history.json` (quirks a + b, now fixed)

- Quirk a -- `uncompleteTodo` deletes `t.completedAt`: the analytics todo fallback
  (`!any && t.completedAt`, app.js `anSourceEvents`) no longer counts a reverted todo.
  `todo_uncomplete_same_day` is the CR-KT-020 `todo_uncomplete_keeps_point` shape: compare it and
  only `after_uncomplete.completedAt` differs (absent now).
- Quirk b -- `unlogToday` no longer gates on the point being dated the current UTC day:
  - `daily_uncomplete_pop_next_day` (was CR-KT-020 `daily_uncomplete_next_day_guard`: point left
    `completed:true`, `_histCreated` stuck) now pops the fresh point;
  - `todo_uncomplete_next_day` retracts the point AND `completedAt`;
  - `todo_uncomplete_across_utc_midnight` pins the reported UTC+2 scenario (complete at
    `2026-10-15T22:30:00Z` = 00:30 local Oct 16; un-tick at `2026-10-16T00:30:00Z` = 02:30 local,
    still the same local day -- but different UTC days, which is what the old gate compared).
- Unchanged: the same-day pop (`daily_uncomplete_pop_same_day` must stay byte-identical to CR-KT-020
  `daily_uncomplete_pop_fresh`); merged points are still KEPT on un-tick (`completed:false`, value
  restored, reward dropped) -- now on later days too (`daily_uncomplete_keep_merged_next_day`).

## Not in this fixture

- `logHistory`'s own same-UTC-day merge bucket is unchanged: CR-PWA-004 scopes only the last point's
  retraction on un-tick. Do not mirror any day-logic change there.
- Event payloads are captured for reference only; Kotlin event-field parity is a separate CR. Not
  covered: missing-`_gr` un-tick fallback, habit taps, cron, import, sync/merge.
- The local-day-vs-UTC-boundary regression also has a direct PWA test that is TZ-independent:
  `tests/untick-retract.test.js` T6.
