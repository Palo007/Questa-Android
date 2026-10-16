package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * CR-KT-022: the "this device has already replayed its completion event log" flag — one-shot
 * state for the boot backfill, so the 200k-row scan runs once per install instead of every
 * launch. Device-only: never synced, never exported (no sync/export/backup code reads it), and
 * its DataStore file is excluded by the Android backup rules (the [DataStorePruneWarnStore]
 * pattern). A lost flag after a backup restore merely re-runs the repair, which is idempotent
 * (UTC-day dedup, add-only) — the same property the Diagnostics re-run relies on.
 */
interface CompletionBackfillStore {
    suspend fun hasRun(): Boolean
    suspend fun markRun()
}

/**
 * CR-KT-023: the one-shot state of the bulk-reps history replay ([com.example.data.RepsReplay]),
 * kept in the same device-only file as the CR-KT-022 flag (excluded from Android backup and
 * device transfer; no sync/export code reads it).
 *
 * [pinBoundary] fixes, ONCE, the highest `event_logs.id` the buggy pre-CR-KT-023 `addHabitReps`
 * can have written: whoever comes first pins it -- the replay (current `MAX(id)`) or the fixed
 * `RpgRepository.addHabitReps` (`MAX(id)` BEFORE its own insert) -- so every row the fixed code
 * writes has an id above the boundary and is never replayed (its history point already exists).
 * [isReplayDone]/[setReplayDone] make the replay run at most once: reps are additive, so a second
 * replay would double count.
 */
interface RepsReplayStore {
    /** Stores [candidate] if no boundary is stored yet; returns the stored boundary. */
    suspend fun pinBoundary(candidate: Long): Long
    suspend fun isReplayDone(): Boolean
    suspend fun setReplayDone(done: Boolean)
}

private val Context.completionBackfillDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreCompletionBackfillStore.FILE_NAME
)

class DataStoreCompletionBackfillStore(private val ds: DataStore<Preferences>) : CompletionBackfillStore, RepsReplayStore {

    override suspend fun hasRun(): Boolean = ds.data.first()[DONE] == true
    override suspend fun markRun() { ds.edit { it[DONE] = true } }

    // CR-KT-023: DataStore's edit is an atomic read-modify-write, so two racing pins agree.
    override suspend fun pinBoundary(candidate: Long): Long {
        var pinned = candidate
        ds.edit { p ->
            val cur = p[REPS_BOUNDARY]
            if (cur == null) p[REPS_BOUNDARY] = candidate else pinned = cur
        }
        return pinned
    }
    override suspend fun isReplayDone(): Boolean = ds.data.first()[REPS_DONE] == true
    override suspend fun setReplayDone(done: Boolean) { ds.edit { it[REPS_DONE] = done } }

    companion object {
        const val FILE_NAME = "questa_completion_backfill"
        private val DONE = booleanPreferencesKey("completion_backfill_done")
        private val REPS_BOUNDARY = longPreferencesKey("reps_replay_boundary_id")
        private val REPS_DONE = booleanPreferencesKey("reps_replay_done")

        fun forApp(ctx: Context): DataStoreCompletionBackfillStore =
            DataStoreCompletionBackfillStore(ctx.applicationContext.completionBackfillDataStore)
    }
}
