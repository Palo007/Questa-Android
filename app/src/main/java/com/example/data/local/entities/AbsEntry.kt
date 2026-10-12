package com.example.data.local.entities

import kotlinx.serialization.Serializable

/**
 * WP-8.2 (Wave 1) -- ported from Opti's `char.abs[deviceId] = {ua, xp, gold, mp}` (sync.js:1699-1712,
 * `_absEntry`, commit 7d5d4f3): the conflict-retry absorption watermark for character totals. Read
 * `_absEntry`'s block comment for why the shape is a device-keyed map, not a scalar: on a 409 retry
 * `_pushWithConflictRetry` re-merges the ALREADY-MERGED local state against the same pristine base,
 * so a plain base-delta reconcile would double-count the remote contribution; `abs[deviceId]`
 * records "the totals of THAT device's snapshot which are already folded into this char" so the
 * next retry doesn't re-add them.
 *
 * `CharacterEntity.abs: Map<String, CharAbsEntry>?` -- null = the field was absent from the PWA
 * original (no conflict retry has ever happened for this character yet).
 *
 * review r2 major 2: `xp`/`mp` are `Double`, not `Int` -- Opti reads every field here through
 * `_num` (`Number(v) || 0`, sync.js:1672), which never throws and accepts a fractional value, and
 * `S.char.xp += xp` (app.js:2231) can itself be fractional. `Converters.toCharAbsMap` decodes each
 * device entry independently using the same permissive `_num` port, so one bad entry (null, a
 * string number, a fractional value where an Int was expected) drops only THAT device's
 * watermark, never every other device's.
 */
@Serializable
data class CharAbsEntry(
    val ua: Long = 0L,
    val xp: Double = 0.0,
    val gold: Double = 0.0,
    val mp: Double = 0.0
)

/**
 * WP-8.2 (Wave 1) -- ported from Opti's `t.cAbs[deviceId] = {ua, cUp, cDown}` (sync.js:1859-1872,
 * `_cAbsEntry`, commit 7d5d4f3): the counter twin of [CharAbsEntry], same conflict-retry-absorption
 * purpose for a habit's `cUp`/`cDown` taps.
 *
 * `TaskEntity.cAbs: Map<String, TaskAbsEntry>?` -- null = absent. Same per-entry, `_num`-permissive
 * decode as [CharAbsEntry] (review r2 major 2).
 */
@Serializable
data class TaskAbsEntry(
    val ua: Long = 0L,
    val cUp: Int = 0,
    val cDown: Int = 0
)
