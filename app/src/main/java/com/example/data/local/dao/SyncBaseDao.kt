package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entities.SyncBaseEntity

/**
 * WP-8.2 (Wave 1, schema v7) -- Room access for the single-row `sync_base` table (D8/D16). Backs
 * [com.example.data.sync.BaseStore]. Uses raw `INSERT OR REPLACE` (not `@Insert`) so
 * `writeAtomic`'s single-row upsert is one statement, matching D8's "the base is never missing
 * mid-write" intent as closely as SQLite's single-writer transaction model allows.
 */
@Dao
interface SyncBaseDao {
    @Query("SELECT * FROM sync_base WHERE id = 0 LIMIT 1")
    suspend fun read(): SyncBaseEntity?

    @Transaction
    suspend fun writeAtomic(appKey: String, accountId: String?, rev: String, frozenJson: String) {
        upsert(SyncBaseEntity(id = 0, appKey = appKey, accountId = accountId, rev = rev, json = frozenJson))
    }

    @Query(
        "INSERT OR REPLACE INTO sync_base (id, appKey, accountId, rev, json) " +
            "VALUES (0, :appKey, :accountId, :rev, :json)"
    )
    suspend fun upsertRaw(appKey: String, accountId: String?, rev: String, json: String)

    suspend fun upsert(entity: SyncBaseEntity) {
        upsertRaw(entity.appKey, entity.accountId, entity.rev, entity.json)
    }

    @Query("DELETE FROM sync_base")
    suspend fun wipe()
}
