package com.example.data.sync

import com.example.data.sync.merge.MergeContext
import kotlinx.serialization.json.JsonObject

/**
 * WP-8.6 -- the round's public result and its plug-in points (PARITY_FIX_PLAN §5 WP-8.6 card).
 *
 * Opti's `syncNow()` resolves to nothing and records the outcome in `syncCfg().lastError`
 * (sync.js:2365-2384). Kotlin returns the outcome instead, and also persists it
 * ([SyncDataStore.lastSyncError], PX-49).
 */
sealed interface SyncResult {
    /** A merged state was uploaded and the base written (sync.js:2513-2517). */
    object Ok : SyncResult { override fun toString() = "Ok" }

    /** merged == remote == base: no upload, base re-written (sync.js:2472-2480). */
    object NothingToPush : SyncResult { override fun toString() = "NothingToPush" }

    /** A remote exists but no base is valid for (APP_KEY, accountId): 0 merges (PX-111). */
    object NeedsFirstConnectChoice : SyncResult { override fun toString() = "NeedsFirstConnectChoice" }

    /** The D7 real-account interlock refused the linked account; 0 requests. */
    object InterlockRefused : SyncResult { override fun toString() = "InterlockRefused" }

    data class Error(val kind: ErrorKind, val msg: String) : SyncResult
}

enum class ErrorKind {
    /** No usable token (Opti returns early from syncNow when `!cfg.refreshToken`). */
    NOT_LINKED,
    /** An HTTP error that is not retried, or the transient ladder is used up. */
    HTTP,
    /** Retry-After longer than [RetryPolicy.RETRY_AFTER_MAX_MS] (sync.js:2493-2496). */
    RATE_LIMITED,
    /** Non-JSON remote, or a `state` that is not an object (sync.js:405-411, PX-47). */
    CORRUPT_REMOTE,
    /** merge() threw, or produced a state without a `tasks` array (sync.js:2455-2458). */
    MERGE_FAILED,
    /** Conflict retries exhausted (sync.js:2542-2548, "sync conflict — retry later"). */
    CONFLICT,
    /** The upload succeeded, but the base write failed ("base snapshot write failed — sync degraded"). */
    BASE_WRITE,
    /** Anything unexpected (an exception inside the round). */
    INTERNAL,
}

/** Who asked for the round. Only [WORKER] skips when another round holds the lock (guess, see WP-8.6 notes). */
enum class SyncTrigger { FOREGROUND, BACKGROUND, NETWORK, DEBOUNCE, BACKOFF, MANUAL, CONNECT, WORKER,
    /** A Settings change (the app key save) that must not run beside a round. Not a sync round. */
    SETTINGS }

/** Where a plug-in step runs inside the round (card "Round order"). */
enum class StepSlot {
    /** After the HLC ratchet, before the local snapshot (Opti: syncInboxConsume, sync.js:2432). */
    AFTER_RATCHET,
    /** After a successful upload or a nothingToPush base write (Opti: syncInboxFinish). */
    POST_UPLOAD,
    /** Event push/pull (Opti: syncEventsSync, only when lastError is clear). */
    EVENTS,
    /** Auto backups (Opti: syncMaybeAutoExport, only when lastError is clear). */
    BACKUPS,
}

sealed interface StepResult {
    object Continue : StepResult
    /** A failed step is recorded, never turns the round into an error (Opti catches each one). */
    data class Failed(val msg: String) : StepResult
}

interface SyncRoundStep {
    val slot: StepSlot
    val order: Int
    suspend fun run(r: RoundContext): StepResult
}

/**
 * The state one round hands to its steps. [local] is the snapshot once it is taken (it is an
 * empty object during [StepSlot.AFTER_RATCHET]); [merged] is null until the merge ran.
 * [accessToken] is the round's token, for steps that call Dropbox.
 */
class RoundContext(
    val remote: DownloadResult.Ok?,
    var local: JsonObject,
    var merged: JsonObject?,
    val ctx: MergeContext,
) {
    var uploaded: Boolean = false
    var accessToken: String = ""
}
