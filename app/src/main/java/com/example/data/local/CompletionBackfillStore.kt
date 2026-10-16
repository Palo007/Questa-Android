package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

private val Context.completionBackfillDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreCompletionBackfillStore.FILE_NAME
)

class DataStoreCompletionBackfillStore(private val ds: DataStore<Preferences>) : CompletionBackfillStore {

    override suspend fun hasRun(): Boolean = ds.data.first()[DONE] == true
    override suspend fun markRun() { ds.edit { it[DONE] = true } }

    companion object {
        const val FILE_NAME = "questa_completion_backfill"
        private val DONE = booleanPreferencesKey("completion_backfill_done")

        fun forApp(ctx: Context): DataStoreCompletionBackfillStore =
            DataStoreCompletionBackfillStore(ctx.applicationContext.completionBackfillDataStore)
    }
}
