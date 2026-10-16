# CR-KT-022: backfill the completion history points from the event log (one-shot repair)

- `backfill_history.json`: generated, do not edit (never edit after ACCEPTED, PROTOCOL P4). Regenerate with:
  `git -C <Opti> show 338d430:app.js > app.js` then `node gen_backfill_history.js app.js > backfill_history.json`.
  Run with TZ=UTC (the script also sets it). `338d430` is the Opti commit that first contains the CR-PWA-004
  un-tick fix; its `app.js` is byte-identical on the main merge `3581f49` ("live"). Do not regenerate from a
  later app.js that changes `unlogToday`/`uncompleteTodo` again; if that commit is ever unavailable, any app.js
  whose `unlogToday` has no day test (retracts the last point) and whose `uncompleteTodo` deletes `completedAt`
  reproduces the same bytes. Byte-stability was verified at generation time: two independent runs produced the
  same sha256 (48E2F9F5..BCADB; recorded in the commit message when vendored).
- The generator does not copy code by hand: the REAL `completeTask`, `uncompleteDaily`, `uncompleteTodo`,
  `logHistory`, `unlogToday` and the helpers (`clamp`, `DIFF`, `valueDelta`, `completionReward`, `isDailyDueOn`,
  `isDailyDueToday`, `dayStamp`) are cut out of Opti `app.js` at run time and run with `new Function` over the
  same tap timeline; the render pins additionally cut `anSourceEvents`, `anBucket`, `anViewItems`, `localDayKey`.
  Stubbed as in CR-KT-020: `S.char` effects, `buzz`, `floatFx`, `fxGain`, `bumpAvatar`, `save`, `render`,
  `toast`; `Date` is frozen per step; the HLC `now()` counts up. The PWA `logEvent` payloads are captured as
  `pwa_events` (reference only).
- Rev 2 (2026-10-16): references re-pinned from afdae43 to 338d430 when CR-PWA-004 went live. The repair spec
  itself is unchanged; the three earlier draft "deviation" cases were reclassified to count-parity (the live
  PWA now retracts un-ticked completions).

## What the repair does (the spec the Kotlin test pins)

1. Per task, `complete` rows pair with `uncomplete` rows in time order, LIFO: an un-tick cancels its completion
   **wherever the days fall** — the LIVE PWA's rule since CR-PWA-004 (the fixed `unlogToday` retracts the last
   point no matter its date: pops it when this completion created it, un-marks + restores the value otherwise;
   `uncompleteTodo` also drops `completedAt`). No reverted completion counts on either side. `complete` rows
   whose `payloadJson` carries `{late:true}` are skipped (creditYesterday wrote its own backdated point,
   RpgEngine.kt:617-667).
2. The standing completions become one point per UTC day (last one wins), written with the `logHistory` merge
   rules: daily `{date, value, completed:true, isDue:true, repeat}`; todo `{date, value, completed:true}`.
   `reward`/`checklist` are omitted — they were never recorded in the event log and are not fabricated.
   `_gr`, `completedAt`, done/streak/`updatedAt` are untouched; `_histCreated` is not stamped.
3. A UTC day that already carries a `completed:true` point is never modified (protects PWA-recorded days;
   idempotent re-runs). A non-completed same-day point (cron miss) is merged in place as `logHistory` would.
4. Add-only: nothing is ever deleted or un-marked.

## Fixture rows

- `cases[]`: `task_before` (the repair-time task — pre-1.3 wrote no points, so its `history` holds only
  pre-existing points; `value`/`streak` as the taps left them), `events` (the Kotlin `event_logs` rows the same
  taps wrote; `id` is insert order — the repair's tiebreak), `expected_history` (the assertion), `pwa_today`
  (the full PWA task after running the same taps through the real PWA code — reference only, harness clocks
  inside), `pwa_events`, and `parity`:
  - `fresh` — repaired points are point-for-point equal to the PWA's modulo the omitted `reward`/`checklist`;
  - `counted` — same completed days (analytics-source rules incl. the todo `completedAt` fallback). Un-tick
    cases with a leftover land here: the fixed PWA keeps an un-marked (`completed:false`) point with the value
    restored where the add-only repair writes nothing — the day counts on neither side (cosmetic only; fresh
    un-tick cases end with both histories empty);
  - `ordered-insert` — an earlier-day point inserted before a later pre-existing one, untouched;
  - `noop` — the history must not change (noise un-tick, dedup, late credit).
- `render[]`: the real PWA analytics (`completed` source, day group, TZ=UTC) over repaired tasks;
  `expected_completed_by_day` = `[{day_ms, v}]`. Pins that reward-less repaired points count exactly like PWA
  points, and that a repaired todo counts via the point alone (no `completedAt`).
- The 13 cases: fresh daily, fresh todo, merge onto today's cron miss, same-day un-tick pop, same-day un-tick
  after a miss merge (cosmetic leftover), late-night un-tick pop (23:50 -> 00:30, next day), todo un-tick days
  later then re-complete (cosmetic leftover), todo same-day un-tick (cosmetic leftover), out-of-order insert,
  same-day todo re-complete, noise un-tick, dedup against a synced point, late credit.

## Alignment with the live PWA (CR-PWA-004)

- References were regenerated from `338d430` — the commit that made CR-PWA-004 live — so `pwa_today` is the
  FIXED behaviour, not the CR-KT-020-era quirks: a next-day un-tick pops a fresh point
  (`daily_late_night_untick_pops`), todo un-ticks un-mark and drop `completedAt` (the two `todo_*` un-tick
  cases). The three earlier draft deviations are gone.
- Where a completion still STANDS the repair reproduces the PWA 1:1; where an un-tick reverted it, both sides
  count no day (the PWA may keep an un-marked leftover the repair does not write).

## Not in this fixture

- The PWA merge behaviour of a backfilled task (changed history, unchanged `updatedAt`) — asked in the CR; the
  Kotlin fallback is the idempotent re-run after sync.
- Habit taps / cron (they already wrote history), import ordering beyond the `id` cursor, and completions whose
  events were already lost to the CR-KT-013/014 prune (unrecoverable by definition).
