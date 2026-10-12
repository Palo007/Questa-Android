package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.SyncPrefsEntity

/**
 * WP-8.10 (WP-8.2 notes minor 12; WP-8.6 guess 6): the DAO for the single `sync_prefs` row
 * (Opti `S.prefs.paused / pausedDays / pausedAt`). Replaces the raw SQL that WP-8.6 used.
 *
 * Row lifecycle (WP-8.3 review, [DEFERRED_OPEN_QUESTION] closed here): un-pausing KEEPS the row
 * (`paused = false`, a fresh `pausedAt`, `pausedDays` kept), as Opti `setPause(false)` keeps the
 * keys in `S.prefs` (app.js:6696). Nothing deletes the row.
 */
@Dao
interface SyncPrefsDao {
    @Query("SELECT * FROM sync_prefs WHERE id = 0 LIMIT 1")
    suspend fun get(): SyncPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncPrefsEntity)
}
