package com.example.data.sync

import com.example.data.sync.auth.InterlockRefused
import com.example.data.sync.backup.BackupPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** WP-8.9 (PX-64) -- the Settings recovery actions (Opti sync.js:2560-2700, :4037-4039). */
interface ForceOps {
    /** Overwrite Dropbox with this device's state, no merge (`syncForcePush`). */
    suspend fun forcePush(): SyncResult
    /** Overwrite this device with Dropbox's state, no merge (`syncForcePull`). */
    suspend fun forcePull(): SyncResult
    /** Re-upload this device's event files, overriding the shrink guard (`syncEventsForcePush`). */
    suspend fun forcePushEvents(): SyncResult
    /**
     * The OAuth-return tail of "Connect & Force Push" (`syncHandleRedirect`, sync.js:241-252):
     * with the pending intent set ([markConnectForForcePush]) the intent is consumed and a force
     * push runs INSTEAD of the normal (merging) round; without it the normal round runs.
     */
    suspend fun connectForForcePush(): SyncResult
}

/**
 * WP-8.9 -- [ForceOps] and the first-connect resolution over 8.6's [SyncEngineInternals].
 *
 * Invariants: every op runs inside [SyncLock.singleFlight] (which waits for the StartupBarrier
 * first), and every Dropbox call goes through [SyncEngineInternals] -> DropboxService, so the D7
 * interlock always applies. An [InterlockRefused] becomes [SyncResult.InterlockRefused].
 * Each outcome is persisted like Opti's `syncCfgSave({lastError, lastSyncAt})` (PX-49).
 */
class ForceOpsEngine(
    private val internals: SyncEngineInternals,
    private val syncDataStore: SyncDataStore,
    /** Opti `syncSubset()` of this device (SyncManager.buildLocalSnapshot). */
    private val localSubset: suspend () -> JsonObject,
    /** Opti `cfg.enabled && cfg.refreshToken` (the same read as BootGate.configuredProvider). */
    private val isConfigured: suspend () -> Boolean,
    /** `ratchetHlc(_maxOrderingTs(remote.state))`; a refusal is swallowed (sync.js:2665). */
    private val ratchet: (Long) -> Unit = {},
    /** `syncEventsPull({force:true})` (8.7 binds); failures are diagnostics only. */
    private val eventsForcePull: (suspend () -> Unit)? = null,
    /** `syncEventsPush({force:true, forceFullPush:true})` (8.7 binds). */
    private val eventsForcePush: (suspend () -> Unit)? = null,
    /** The normal round for [connectForForcePush] without the intent (`syncNow()`). */
    private val normalSync: suspend () -> SyncResult? = { null },
    private val backupPrefs: BackupPrefs? = null,
    private val wrapperClock: () -> Long = SyncClocks.wrapperSavedAt,
) : ForceOps {

    companion object {
        /** Opti `SYNC_CONFLICT_RETRY_LIMIT` (sync.js:46). */
        const val CONFLICT_RETRY_LIMIT = 3
        const val NOTHING_TO_PULL = "nothing in Dropbox yet to pull"
        const val PULL_CORRUPT = "remote corrupt — force pull aborted, local data untouched"
        const val NOT_LINKED_MESSAGE = "Dropbox is not linked or token is invalid"
    }

    /** Runs [block] under the one sync lock; interlock refusals and failures are persisted. */
    private suspend fun locked(block: suspend () -> SyncResult): SyncResult {
        val r = SyncLock.singleFlight(SyncTrigger.MANUAL) {
            try {
                block()
            } catch (e: InterlockRefused) {
                SyncResult.InterlockRefused
            }
        } ?: SyncResult.Error(ErrorKind.INTERNAL, "A sync round is already running")
        if (r == SyncResult.InterlockRefused) {
            try { syncDataStore.saveSyncStatus(SyncManager.INTERLOCK_REFUSED_MESSAGE) } catch (e: Exception) { }
        }
        return r
    }

    private suspend fun fail(prefix: String, e: Throwable): SyncResult {
        val msg = prefix + (e.message ?: e.toString())
        syncDataStore.saveSyncStatus(msg)
        return SyncResult.Error(ErrorKind.INTERNAL, msg)
    }

    override suspend fun forcePush(): SyncResult = locked { forcePushLocked() }

    override suspend fun forcePull(): SyncResult = locked { forcePullLocked() }

    /** `_syncForcePushAttempt(attempt)` (sync.js:2587-2616). Caller holds the lock. */
    internal suspend fun forcePushLocked(): SyncResult {
        if (!isConfigured()) return SyncResult.Error(ErrorKind.NOT_LINKED, NOT_LINKED_MESSAGE)
        var attempt = 0
        while (true) {
            // Only the current rev: the content is overwritten unconditionally.
            val rev: String? = try {
                when (val dl = internals.download()) {
                    is DownloadResult.Ok -> dl.rev
                    else -> null // an add-mode upload 409s into the retry if a file does exist
                }
            } catch (e: CancellationException) { throw e } catch (e: InterlockRefused) { throw e } catch (e: Exception) { null }
            val r = pushOnceAgainst(rev)
            if (r != null) return r
            if (attempt < CONFLICT_RETRY_LIMIT) { attempt++; continue }
            return conflictExhausted()
        }
    }

    /** The conflict retries are used up (`"force push failed: sync conflict"`). */
    internal suspend fun conflictExhausted(): SyncResult {
        val msg = "force push failed: sync conflict"
        syncDataStore.saveSyncStatus(msg)
        return SyncResult.Error(ErrorKind.CONFLICT, msg)
    }

    /**
     * One upload of the local subset against exactly [rev] (null = add mode), then the base.
     * Returns null on a rev conflict (the caller decides whether to retry). Caller holds the lock.
     */
    internal suspend fun pushOnceAgainst(rev: String?): SyncResult? {
        if (!isConfigured()) return SyncResult.Error(ErrorKind.NOT_LINKED, NOT_LINKED_MESSAGE)
        try {
            val body = CanonicalJson.canonicalJsonString(localSubset())
            return when (val up = internals.uploadFrozen(body, rev)) {
                is UploadOutcome.Ok -> {
                    val baseOk = try { internals.writeBase(up.rev, body); true } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                    syncDataStore.saveSyncStatus(if (baseOk) null else SyncManager.BASE_WRITE_FAILED_MESSAGE, wrapperClock())
                    if (baseOk) SyncResult.Ok else SyncResult.Error(ErrorKind.BASE_WRITE, SyncManager.BASE_WRITE_FAILED_MESSAGE)
                }
                UploadOutcome.Conflict -> null
                is UploadOutcome.Http -> {
                    val msg = "force push failed: " + SyncManager.httpErrorText("upload failed", up.status, up.summary)
                    syncDataStore.saveSyncStatus(msg)
                    SyncResult.Error(ErrorKind.HTTP, msg)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterlockRefused) {
            throw e
        } catch (e: Exception) {
            return fail("force push failed: ", e)
        }
    }

    /** `_syncForcePullAttempt()` (sync.js:2648-2686). Caller holds the lock. */
    internal suspend fun forcePullLocked(): SyncResult {
        if (!isConfigured()) return SyncResult.Error(ErrorKind.NOT_LINKED, NOT_LINKED_MESSAGE)
        try {
            val remote = when (val dl = internals.download()) {
                is DownloadResult.Ok -> dl
                DownloadResult.NotFound -> {
                    syncDataStore.saveSyncStatus(NOTHING_TO_PULL)
                    return SyncResult.Error(ErrorKind.INTERNAL, NOTHING_TO_PULL)
                }
                is DownloadResult.Corrupt -> {
                    syncDataStore.saveSyncStatus(PULL_CORRUPT)
                    return SyncResult.Error(ErrorKind.CORRUPT_REMOTE, PULL_CORRUPT)
                }
                is DownloadResult.Http -> {
                    val msg = "force pull failed: " + SyncManager.httpErrorText("download failed", dl.status, dl.summary)
                    syncDataStore.saveSyncStatus(msg)
                    return SyncResult.Error(ErrorKind.HTTP, msg)
                }
            }
            val state = remote.json["state"] as? JsonObject
            if (state == null || state["tasks"] !is JsonArray) {
                syncDataStore.saveSyncStatus(PULL_CORRUPT)
                return SyncResult.Error(ErrorKind.CORRUPT_REMOTE, PULL_CORRUPT)
            }
            try { ratchet(RoundMath.maxOrderingTs(state)) } catch (e: Exception) { }
            internals.applyRemoteAsLocal(state) // local becomes remote's content, unconditionally
            val baseOk = try {
                internals.writeBase(remote.rev, CanonicalJson.canonicalJsonString(state)); true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            syncDataStore.saveSyncStatus(if (baseOk) null else SyncManager.BASE_WRITE_FAILED_MESSAGE, wrapperClock())
            // W6.15: pull events too (force), a failure never fails the state pull.
            eventsForcePull?.let { pull -> try { pull() } catch (e: CancellationException) { throw e } catch (e: Exception) { } }
            return if (baseOk) SyncResult.Ok else SyncResult.Error(ErrorKind.BASE_WRITE, SyncManager.BASE_WRITE_FAILED_MESSAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterlockRefused) {
            throw e
        } catch (e: Exception) {
            return fail("force pull failed: ", e)
        }
    }

    /** `syncEventsForcePush()` under the lock; a failure is `"event force push failed: …"` (sync.js:3763-3766). */
    override suspend fun forcePushEvents(): SyncResult = locked {
        val push = eventsForcePush ?: return@locked SyncResult.Error(ErrorKind.INTERNAL, "event force push is not available")
        if (!isConfigured()) return@locked SyncResult.Error(ErrorKind.NOT_LINKED, NOT_LINKED_MESSAGE)
        try {
            push()
            SyncResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterlockRefused) {
            throw e
        } catch (e: Exception) {
            fail("event force push failed: ", e)
        }
    }

    /** `confirmConnectForForcePush()`'s intent flag (set BEFORE the OAuth redirect). */
    suspend fun markConnectForForcePush() {
        backupPrefs?.setPendingForcePush(true)
    }

    /** A plain connect, a failed redirect, or a normal boot drops a leftover intent (sync.js:141-143, :285, :3806). */
    suspend fun clearPendingForcePush() {
        backupPrefs?.setPendingForcePush(false)
    }

    override suspend fun connectForForcePush(): SyncResult {
        val pending = backupPrefs?.pendingForcePush() == true
        backupPrefs?.setPendingForcePush(false)
        return if (pending) forcePush() else (normalSync() ?: SyncResult.Error(ErrorKind.INTERNAL, "A sync round is already running"))
    }
}
