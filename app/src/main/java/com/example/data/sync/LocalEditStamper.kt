package com.example.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * CR-KT-018: Opti `_noteSaveCommitted` (app.js:335-342) stamps `localEditAt = Date.now()` on every
 * USER save and skips a syncApply save. Here the user-write signal is
 * [com.example.data.RpgRepository.localWrites], which a merge apply does not emit
 * ([com.example.data.RpgRepository.syncApplying]), so the skip comes for free.
 *
 * [clock] defaults to [SyncClocks.wrapperSavedAt], the clock that stamps `lastSyncAt`, so both
 * sides of the badge's unsynced rule share one base (Opti: both `Date.now()`).
 */
class LocalEditStamper(
    private val localWrites: Flow<Unit>,
    private val stamp: suspend (Long) -> Unit,
    private val clock: () -> Long = SyncClocks.wrapperSavedAt,
) {
    /** Starts collecting in [scope]. Call once per process, before user writes can happen. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        localWrites.collect {
            // Opti wraps the localStorage write in try/catch: a failed stamp never breaks a save.
            try {
                stamp(clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }
}
