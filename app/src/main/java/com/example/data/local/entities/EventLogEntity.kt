package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "event_logs")
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long, // WP-8.23: no clock default; the caller passes the physical stamp
    val kind: String, // e.g. "complete", "habitTap", "miss", "create", "edit", "purchase"
    val taskType: String? = null,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val streak: Int? = null,
    val damage: Double? = null,
    val direction: Int? = null, // 1 for cUp, -1 for cDown
    val reps: Int? = null,
    val value: Double? = null,
    val cost: Double? = null,
    val payloadJson: String? = null, // Other metadata serialized as JSON if needed

    // Stage 4 / T4.2-T4.3 (spec §7 D6; spec §1.2 "Event object" base order `ts -> uid -> dev ->
    // [kind-specific]"; §3 addendum). PWA's logEvent() (app.js:644-654) always stamps these two
    // at event-creation time via Object.assign; this Kotlin entity had no columns for them at all
    // prior to Stage 4, so no event was ever eligible for the Dropbox `/events/*.json` pipeline
    // (spec §3 addendum rule 1's `e.uid && e.dev === myDev` guard). `RpgRepository.insertLog` is
    // the single chokepoint every EventLogEntity construction in this codebase already funnels
    // through, so it is the Kotlin equivalent of logEvent() and stamps `uid`/`dev` there if
    // missing (see its KDoc) -- these two columns are nullable here only to describe the
    // pre-Stage-4 historical rows (and any row inserted via a path that bypasses the repository,
    // none currently exist) that predate the stamp and must never retroactively become
    // "uploadable" (rule 1 requires `e.uid` truthy).
    val uid: String? = null,
    val dev: String? = null,
    // WP-8.2 (schema v7, D9) -- new typed columns backfilled from `payloadJson` by MIGRATION_6_7.
    // null = absent (mirrors Opti's optional per-event `imported`/`republish` flags).
    val imported: Boolean? = null,
    val republish: Boolean? = null,
    // Spec §3 addendum rule 1/17/25: `!e.synthetic` is part of both the push-eligibility filter
    // and the pull ingest filter. PWA's own local-only diagnostic kind is the string literal
    // `"lifecycle"` (excluded by a separate, kind-based check); this project's own Kotlin-only
    // sync diagnostic log kinds ("syncCompleted", "clockSkew" -- see SyncManager.kt) are marked
    // `synthetic = true` at their construction site for the identical reason PWA's own comment at
    // sync.js:1420-1426 gives for excluding "lifecycle": a Kotlin-internal diagnostic entry must
    // never leak into another device's Activity Feed via the shared `/events/*.json` pipeline.
    val synthetic: Boolean = false
)
