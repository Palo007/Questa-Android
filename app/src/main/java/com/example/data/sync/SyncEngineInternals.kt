package com.example.data.sync

import kotlinx.serialization.json.JsonObject

/**
 * WP-8.6 -- the round primitives that 8.9's force push / force pull / first-connect reuse
 * (Opti `_syncForcePushAttempt` / `_syncForcePullAttempt`, sync.js:2560-2700). Each call goes
 * through the D7 interlock inside DropboxService. None of them takes [SyncLock]: the caller
 * runs them inside `SyncLock.singleFlight` (Opti `_withSyncLock`, sync.js:2342).
 */
interface SyncEngineInternals {
    /** Download `/state.json` with the round's token (NotFound = no file yet). */
    suspend fun download(): DownloadResult

    /** Upload [body] (a frozen `state` JSON string, wrapped here) in update mode at [rev], or add mode when null. */
    suspend fun uploadFrozen(body: String, rev: String?): UploadOutcome

    /** Apply [remote] (a `state` object) to Room as Opti `syncApply(raw remote)` does. */
    suspend fun applyRemoteAsLocal(remote: JsonObject)

    /** Write the base for (APP_KEY, linked account) atomically. [body] is the frozen state string. */
    suspend fun writeBase(rev: String, body: String)
}
