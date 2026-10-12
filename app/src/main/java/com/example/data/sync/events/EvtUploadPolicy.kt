package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity

/**
 * WP-8.7 (PX-51, PX-52; SY-33, SY-34) -- which own events go up, and where the push watermark may
 * land. Opti `evtUploadable` / `evtOwnMonthRecords` (sync.js:3062-3078) and the clamp at
 * sync.js:3396-3400.
 */
object EvtUploadPolicy {
    /** Opti `MAX_FUTURE_SKEW_MS` (sync.js:43, = HLC_RATCHET_TOLERANCE_MS, 120 s). */
    const val MAX_FUTURE_SKEW_MS: Long = 120_000L

    /** Opti's 24 h full re-push interval (sync.js:3307, `> 86400000`). */
    const val FULL_PUSH_INTERVAL_MS: Long = 86_400_000L

    /** Opti `EVENT_AGE_LIMIT_MS` fallback, `18 * 30 * 86400000` (never defined in sync.js itself). */
    const val EVENT_AGE_LIMIT_MS: Long = 18L * 30L * 86_400_000L

    /**
     * The ownership rule shared by both functions: stamped, mine, real (not synthetic), not an
     * unpublished import, not the local-only `lifecycle` kind. `synthetic` is unconditional;
     * `imported` is lifted only by an explicit `republish` (sync.js:3050-3061).
     */
    fun ownMonthRecord(e: EventLogEntity, myDev: String): Boolean =
        !e.uid.isNullOrEmpty() && e.dev == myDev && !e.synthetic &&
            (e.imported != true || e.republish == true) && e.kind != "lifecycle"

    /** `evtUploadable`: [ownMonthRecord] and newer than the watermark (`e.ts > sinceTs`). */
    fun uploadable(e: EventLogEntity, myDev: String, sinceTs: Long): Boolean =
        ownMonthRecord(e, myDev) && e.timestamp > sinceTs

    /**
     * `min(min(maxTs, now + MAX_FUTURE_SKEW_MS), now)`: an event stamped in the future never pins
     * the watermark past now (PX-51).
     */
    fun watermark(maxTs: Long, now: Long): Long = minOf(minOf(maxTs, now + MAX_FUTURE_SKEW_MS), now)
}
