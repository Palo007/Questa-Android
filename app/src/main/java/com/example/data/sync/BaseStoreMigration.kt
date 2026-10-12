package com.example.data.sync

import com.example.data.local.dao.TombstoneDao
import com.example.data.local.entities.TombstoneEntity
import kotlinx.coroutines.flow.first

/**
 * WP-8.2 (Wave 1, schema v7) -- §4.3: one-time, idempotent move of the legacy DataStore sync
 * metadata into their new Room homes.
 *
 * - `DROPBOX_BASE_SNAPSHOT` + `DROPBOX_LAST_REV` -> [BaseStore] row for `dataAppKey`.
 * - `LOCAL_DELETIONS` -> `tombstones` table, unioned with any existing tombstone by id, keeping
 *   the larger `at` (a tombstone already written locally by a DAO delete call is never regressed
 *   to an older DataStore-recorded deletion time).
 * - The base/rev keys are always cleared. `LOCAL_DELETIONS` is cleared ONLY when EVERY element was
 *   read successfully (review r1 major 7, tightened by review r2 minor 3): if ANY element fails to
 *   parse -- the whole value isn't a JSON array, or just one element among several -- the good
 *   entries still migrate, but the key is left in place holding the still-unparsed leftover
 *   (never the already-migrated good ones), so a later `runIfNeeded` call gets another chance at
 *   the bad element and no deletion is ever silently dropped.
 *
 * This class only MOVES data; it is not part of the D21 upgrade-detection decision (that is
 * WP-8.21's `StartupMigrations`, which decides *whether* a device is a legacy install and calls
 * `runIfNeeded` as one of its steps). Calling `runIfNeeded` on a fresh install (no legacy keys) is
 * a correct, cheap no-op, and calling it a second time is also a no-op (the keys are already gone).
 */
class BaseStoreMigration(
    private val dataStore: SyncDataStore,
    private val baseStore: BaseStore,
    private val tombstones: TombstoneDao
) {
    suspend fun runIfNeeded(dataAppKey: String) {
        val rev = dataStore.dropboxLastRev.first()
        val snapshot = dataStore.dropboxBaseSnapshot.first()

        if (rev != null && snapshot != null) {
            baseStore.writeAtomic(appKey = dataAppKey, accountId = null, rev = rev, frozenJson = snapshot)
        }
        // A rev with no base (or vice versa) leaves the base absent (D8: the round will then
        // demand NeedsFirstConnectChoice) -- never write a half-formed base row.
        dataStore.clearBaseSnapshotKeys()

        val recovery = dataStore.localDeletionsRecovery()
        for ((id, ts) in recovery.recovered) {
            val existing = tombstones.getTombstoneById(id)
            val keepAt = if (existing != null) maxOf(existing.at, ts) else ts
            tombstones.insertTombstone(TombstoneEntity(id = id, at = keepAt))
        }
        if (recovery.anyFailure) {
            // review r2 minor 3: never drop a deletion silently. Keep the key, holding only the
            // still-unparsed leftover (the good entries above already migrated and must not be
            // re-processed next time).
            recovery.leftoverJson?.let { dataStore.setLocalDeletionsRaw(it) }
        } else {
            dataStore.clearLocalDeletionsKey()
        }
    }
}
