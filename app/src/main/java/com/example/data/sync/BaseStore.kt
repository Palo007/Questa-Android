package com.example.data.sync

import com.example.data.local.dao.SyncBaseDao
import com.example.data.local.entities.SyncBaseEntity

/**
 * WP-8.2 (Wave 1, "Schema v7, clock and persistence foundation") -- D8/D16: the Room-backed home
 * for the sync "base" (the last snapshot both this device and Dropbox agree was uploaded).
 *
 * Replaces the pre-8.2 DataStore pair `DROPBOX_BASE_SNAPSHOT`/`DROPBOX_LAST_REV` (§4.3), moved
 * here by [BaseStoreMigration]. WP-8.6 (round orchestration) is the only writer of [writeAtomic]
 * during normal operation, and the only reader of [validFor]/[read] before a merge (D8: "the base
 * is never missing when a merge runs" -- enforced by [StartupBarrier], not by this interface).
 */
interface BaseStore {
    /** The current base row, if any, regardless of which (appKey, accountId) it belongs to. */
    suspend fun read(): SyncBaseEntity?

    /**
     * The current base row, but only if it is bound to [appKey]/[accountId] (D8: reconnecting a
     * different account must not silently reuse a stale base). `accountId == null` matches a base
     * row whose own `accountId` is also null (pre-account-interlock installs, WP-8.1).
     */
    suspend fun validFor(appKey: String, accountId: String?): SyncBaseEntity?

    /** Atomically replaces the single base row. [frozenJson] must already be canonicalized (WP-8.3). */
    suspend fun writeAtomic(appKey: String, accountId: String?, rev: String, frozenJson: String)

    /** Removes the base row entirely (D8: Reset wipes the base, app.js:6630). */
    suspend fun wipe()
}

class RoomBaseStore(private val dao: SyncBaseDao) : BaseStore {
    override suspend fun read(): SyncBaseEntity? = dao.read()

    override suspend fun validFor(appKey: String, accountId: String?): SyncBaseEntity? {
        val base = dao.read() ?: return null
        return if (base.appKey == appKey && base.accountId == accountId) base else null
    }

    override suspend fun writeAtomic(appKey: String, accountId: String?, rev: String, frozenJson: String) {
        dao.writeAtomic(appKey, accountId, rev, frozenJson)
    }

    override suspend fun wipe() {
        dao.wipe()
    }
}
