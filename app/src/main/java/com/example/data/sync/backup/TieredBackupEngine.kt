package com.example.data.sync.backup

import com.example.data.clock.PhysicalClock
import com.example.data.sync.DeleteResult
import com.example.data.sync.DropboxService
import com.example.data.sync.RoundContext
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncRoundStep
import com.example.data.sync.UploadOutcome
import kotlinx.coroutines.CancellationException
import java.util.TimeZone

/** The three Dropbox calls a tier fire needs. Every production call goes through [DropboxService] (D7 interlock). */
interface BackupRemote {
    /** File names in [folder] (all pages). Throws on failure. */
    suspend fun list(folder: String): List<String>
    /** `dbxUploadText(path, text)`: overwrite mode. Throws on failure. */
    suspend fun upload(path: String, text: String)
    /** `dbxDelete(path)`. Throws on failure. */
    suspend fun delete(path: String)
}

class BackupRemoteException(msg: String) : Exception(msg)

/** [BackupRemote] over [DropboxService] with the round's access token. */
class DropboxBackupRemote(private val accessToken: String) : BackupRemote {
    override suspend fun list(folder: String): List<String> {
        var page = DropboxService.listFolder(accessToken, folder) ?: throw BackupRemoteException("list failed")
        val out = page.entries.map { it.name }.toMutableList()
        while (page.hasMore) {
            val cursor = page.cursor ?: break
            page = DropboxService.listFolderContinue(accessToken, cursor) ?: throw BackupRemoteException("list failed")
            out += page.entries.map { it.name }
        }
        return out
    }

    override suspend fun upload(path: String, text: String) {
        when (val r = DropboxService.uploadOverwrite(accessToken, path, text)) {
            is UploadOutcome.Ok -> Unit
            is UploadOutcome.Http -> throw BackupRemoteException(
                "Dropbox backup upload failed: " + r.status + (r.summary?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
            )
            UploadOutcome.Conflict -> throw BackupRemoteException("Dropbox backup upload failed: 409")
        }
    }

    override suspend fun delete(path: String) {
        val r = DropboxService.delete(accessToken, path)
        if (r is DeleteResult.Http) throw BackupRemoteException("delete failed: " + r.status)
    }
}

/**
 * WP-8.9 (PX-63) -- Opti's tiered Dropbox backups: `syncMaybeAutoExport` + `_bkFire`
 * (sync.js:2859-3006). It rides the round's BACKUPS step (only after a round with no lastError).
 * One backup text is built per call and every due tier uploads it to its next slot; the slot
 * rotation then trims the tier. A tier failure writes `lastBackupError` (its own key, never the
 * round's lastError); a later success of any tier clears it.
 *
 * E4: Opti's local Tier-1 snapshots (`takeSnapshot`, GFS) are excluded and not ported.
 */
class TieredBackupEngine(
    private val prefs: BackupPrefs,
    /** The backup file text (Opti `buildBackupFile(eventsArr).blob`); null = nothing to write. */
    private val buildBackupText: suspend () -> String?,
    private val deviceId: suspend () -> String,
    private val saveLastExportTs: suspend (Long) -> Unit,
    private val lastBackupError: suspend () -> String?,
    private val saveLastBackupError: suspend (String?) -> Unit,
    /** `logEvent({kind:'export', taskTitle, notes})`. */
    private val logExport: suspend (title: String, notes: String) -> Unit,
    private val clock: () -> Long = { PhysicalClock.nowMs() }, // clock-ok:backup-local-stamp
    private val tz: () -> TimeZone = { TimeZone.getDefault() },
) {
    companion object {
        /** Opti `EXPORT_BACKUP_PATH` (manual "Save to Dropbox", sync.js:2704). */
        const val EXPORT_BACKUP_PATH = "/export-backup.json"
    }

    private val inFlight = HashSet<BkTier>()

    /** `syncMaybeAutoExport()`: best effort, never throws (except cancellation). */
    suspend fun maybeAutoExport(remote: BackupRemote) {
        try {
            val enabled = prefs.autoBackupEnabled()
            val now = clock()
            val bk = prefs.tierStates()
            val due = BkTier.ORDER.filter { t ->
                enabled.isOn(t) && now >= BackupTierEngine.nextBoundary(t, bk.getValue(t).lastTs, tz())
            }
            if (due.isEmpty()) return
            val text = buildBackupText() ?: return
            for (t in due) fire(t, text, remote)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best-effort only, never throw into the round (sync.js:3003)
        }
    }

    /** `_bkListTier`'s listing: any failure is an empty list (sync.js:2815-2819). */
    private suspend fun listSafe(remote: BackupRemote): List<String> = try {
        remote.list(BackupTierEngine.FOLDER)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** `_bkFire(tierKey, blob)`: upload + slot rotation (sync.js:2859-2968). */
    suspend fun fire(t: BkTier, text: String, remote: BackupRemote) {
        synchronized(inFlight) { if (!inFlight.add(t)) return }
        try {
            val dev6 = BackupTierEngine.deviceShort(deviceId())
            val now = clock()
            val states = prefs.tierStates().toMutableMap()
            var st = states.getValue(t)
            // Lazy self-heal: bookkeeping unknown -> list Dropbox and recover the real slot.
            if (st.bkVersion < 1) {
                st = try {
                    val existing = listSafe(remote)
                    val healed = TierState(bkVersion = 1, lastTs = now, idx = BackupTierEngine.healedIndex(existing, t, dev6))
                    states[t] = healed
                    prefs.saveTierStates(states)
                    healed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    st.copy(bkVersion = 1).also { states[t] = it }
                }
            }
            val slot = st.idx
            val path = BackupTierEngine.path(t, dev6, slot, BackupTierEngine.stamp(now, tz()))
            remote.upload(path, text)

            val listing = listSafe(remote)
            for (p in BackupTierEngine.rotation(listing, t, dev6, slot, path)) {
                try { remote.delete(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* best-effort */ }
            }

            states[t] = st.copy(idx = (slot + 1) % t.count, lastTs = now)
            prefs.saveTierStates(states)
            saveLastExportTs(clock())
            if (lastBackupError() != null) saveLastBackupError(null) // this tier is healthy again
            logExport("Auto Backup (" + t.prefix + ")", "Uploaded $path to Dropbox")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveLastBackupError(t.key + " backup failed: " + (e.message ?: e.toString()))
        } finally {
            synchronized(inFlight) { inFlight.remove(t) }
        }
    }

    /** Manual "Save to Dropbox" (`syncUploadBackupBlob`, sync.js:2733-2741). Throws on failure. */
    suspend fun uploadManual(text: String, remote: BackupRemote) {
        remote.upload(EXPORT_BACKUP_PATH, text)
        saveLastExportTs(clock())
    }
}

/** The BACKUPS round step (8.I composes it into `SyncManager(roundSteps = ...)`). */
class AutoBackupStep(
    private val engine: TieredBackupEngine,
    private val remoteFor: (accessToken: String) -> BackupRemote = { DropboxBackupRemote(it) },
) : SyncRoundStep {
    override val slot = StepSlot.BACKUPS
    override val order = 0
    override suspend fun run(r: RoundContext): StepResult {
        engine.maybeAutoExport(remoteFor(r.accessToken))
        return StepResult.Continue
    }
}
