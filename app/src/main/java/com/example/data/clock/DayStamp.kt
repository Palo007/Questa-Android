package com.example.data.clock

/**
 * WP-8.2 (Wave 1, "Schema v7, clock and persistence foundation"): the shared, non-HLC local day
 * key used throughout the merge/rollover/reminder machinery specified across the Stage 8 parity
 * plan (PARITY_FIX_PLAN.html §5.0: "typealias DayStamp = Int means local YYYYMMDD", app.js:2025).
 *
 * `DayStamp` is deliberately just `Int`, not a value class, so it round-trips through Room typed
 * columns (`INTEGER`) and kotlinx.serialization without a custom converter. An empty/unset day is
 * represented as `0` (matches Opti's number-vs-`""` comparison for a fresh account, PARITY_FIX_PLAN
 * §5.0 r8 m-R7-3): `today <= 0` is always false.
 *
 * This file is the single canonical home for the typealias. It is introduced here (Wave 1) because
 * WP-8.2's own new typed columns (`TaskEntity.doneDay`, `TaskEntity.cResetOn`,
 * `SyncPrefsEntity.pausedDays`) already need the type; later waves (WP-8.5 `MergeContext`,
 * WP-8.6 `BootGate`/§5.0, WP-8.10 `RpgEngine.runCron`, WP-8.11 reminders) reuse it unchanged.
 */
typealias DayStamp = Int
