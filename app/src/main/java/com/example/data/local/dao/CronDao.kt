package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.TaskEntity

/** WP-8.10 (§5.0 B10): the committed Room state that `startDay` and `runCron` decide on. */
data class CronInputs(
    val character: CharacterEntity?,
    val tasks: List<TaskEntity>,
    val prefs: SyncPrefsEntity?,
)

/**
 * WP-8.10 (§5.0 B10): ONE suspend read of the cron inputs (tasks, pause, `lastCron`), in one
 * read transaction, so the three parts come from the same committed state. Not a `Flow`.
 */
@Dao
abstract class CronDao {
    @Query("SELECT * FROM character WHERE id = 1 LIMIT 1")
    protected abstract suspend fun character(): CharacterEntity?

    @Query("SELECT * FROM tasks ORDER BY orderIndex ASC, createdAt DESC")
    protected abstract suspend fun tasks(): List<TaskEntity>

    @Query("SELECT * FROM sync_prefs WHERE id = 0 LIMIT 1")
    protected abstract suspend fun prefs(): SyncPrefsEntity?

    @Transaction
    open suspend fun cronInputsOnce(): CronInputs = CronInputs(character(), tasks(), prefs())
}
