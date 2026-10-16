# CR-KT-023: write the bulk-reps history point and replay old habitReps rows

- `reps_history.json`: generated, do not edit (never edit after ACCEPTED, PROTOCOL P4). Regenerate with:
  `git -C <Opti> show 3581f49:app.js > app.js` then `node gen_reps_history.js app.js > reps_history.json`.
  Run with TZ=UTC (the script also sets it). Byte-stability was verified at generation time: two independent
  runs produced the same sha256 (0C72C043..9B24D1; recorded in the commit message when vendored).
- The generator does not copy code by hand: the REAL `addReps`, `scoreHabit`, `logHistory` and the helpers
  (`clamp`, `DIFF`, `valueDelta`, `completionReward`, `missDamage`, `repsPerTap`) are cut out of Opti `app.js`
  at run time and run with `new Function`; the render pins additionally cut `localDayKey`, `anViewItems`,
  `anAllEvents`, `anMatcher`, `anViewMetric`, `anSourceEvents`, `anBucket`. Stubbed: `S.char` effects,
  `buzz`, `floatFx`, `fxGain`, `bumpAvatar`, `save`, `render`, `toast`, `bootGateBlocksInput`; `Date` is frozen
  per step; the HLC `now()` counts up. Every case is asserted in the generator; zero mismatches.

## The bug

Pre-CR-KT-023 `MainViewModel.addHabitReps` bumped `cUp`/`cDown` and logged a `habitReps` row but wrote no
history point. The PWA `addReps(id, n)` (n signed) writes
`logHistory(t, {value: t.value, reps: n, repCounted: true, scored: false})`. Analytics' reps metric reads only
history points, so bulk reps logged on Android were invisible there (and never reached the PWA).

## Part A: forward fix (`forward[]`, 5 cases)

`RpgEngine.addReps(task, n, now, atMs)` must equal the PWA `addReps` for the same task and signed n: `history`,
`cUp`, `cDown`, `value`. Cases: +n on a fresh day, -n, merge onto today's tap point, merge onto a scored
(non-log) habit point (`scored` stays true), merge onto a legacy point without reps/repCounted/scored keys.

## Part B: one-shot replay (`replay[]`, 8 cases) -- the spec the Kotlin test pins

1. Rows: `kind = habitReps`, `dev` = this device's id, `imported` not true, not synthetic, `id <= boundary_id`,
   order `timestamp ASC, id ASC`. Pulled rows (other `dev`; EventPuller also skips its own `dev`) and imported
   rows came from the PWA, which already wrote the point. The boundary is pinned once, by whoever runs first:
   the replay (`MAX(id)`) or the first fixed `addHabitReps` (`MAX(id)` before its insert), so a fixed row is
   never replayed.
2. Each row is the `addReps` patch with `n` signed (`direction < 0` -> `-|reps|`), dated `row.timestamp`.
3. A UTC day that already has a point (the last one of that day): `logHistory`'s merge -- `reps` adds,
   `repCounted` true, `scored` = `last.scored || false`; `date`/`value` move to the row only when the row is not
   older than the point (the later tap that merged there keeps its own, as it would in the PWA).
4. Otherwise a new point `{date, value, reps, repCounted:true, scored:false}` inserted in date order.
5. Add-only; counters/value/`updatedAt`/`_gr`/done/streak untouched. Runs once (done flag): reps add up, so a
   second fold would double count (`replay_rerun_noop` asserts that it would).

Each replay case runs one tap/reps tape twice: through the real PWA code (`pwa_history`, the reference) and as
the buggy Kotlin wrote it (`task_before` + `events`). The generator asserts `expected_history` equals
`pwa_history` key-order-free, point for point (no omitted fields). Cases: fresh days; minus + same-day merge;
merge onto a log tap point (a later tap keeps its date); merge onto a scored point; out-of-order insert between
existing points (two rows of one day become one point); foreign rows excluded (pulled PWA row, PWA backup row
with `imported:true`, a local `habitTap` row); post-fix rows above the boundary excluded; re-run no-op.

## Render pins (`render[]`, 2)

The real PWA analytics (`metric` source, day group, TZ=UTC) over replayed tasks: `expected_metric_by_day`
(asserted equal to the PWA-history result and different from the buggy one, `buggy_metric_by_day`). A keyword
metric and an exact metric with a per-tap override.

## Not in this fixture

- `habitReps` rows with no `dev` (written before Stage 4 stamped uid/dev): their origin cannot be told from a
  legacy imported row, so they are NOT replayed ([DEFERRED_OPEN_QUESTION]; undercount, never double count).
- Rows of an earlier install restored on a new phone (StartupMigrations.restoreReset gives a new device id):
  not replayed for the same reason.
- The `habitReps` event payload (`reps` = |n| + `direction` vs the PWA's signed `reps: n` + `dir`): unchanged.
