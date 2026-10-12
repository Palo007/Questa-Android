package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    // Stage 3 / T3.5 (D12, opus-review finding #3): ordered by the persisted `orderIndex` ordinal
    // (see TaskEntity.orderIndex's doc), not `createdAt DESC` -- the previous ORDER BY clause had
    // no memory of the array position a task actually held in a last-received/last-merged
    // snapshot, so a re-export's order could drift from the PWA's real array order even after
    // T3.1 made each element's own fields round-trip losslessly. `createdAt DESC` kept only as a
    // tie-break for legacy rows sharing the same `orderIndex` (e.g. immediately post-migration
    // backfill collisions), matching the pre-T3.5 sort as closely as possible in that edge case.
    @Query("SELECT * FROM tasks ORDER BY orderIndex ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY orderIndex ASC, createdAt DESC")
    suspend fun getAllTasksSync(): List<TaskEntity>

    // Stage 3 / T3.5: used by RpgRepository.nextTaskOrderIndex() to mirror PWA's
    // `S.tasks.unshift(EDIT)` (app.js:4545) -- a brand new task must sort BEFORE every existing
    // one, so its ordinal is one less than the current minimum (0 if the table is empty).
    @Query("SELECT MIN(orderIndex) FROM tasks")
    suspend fun getMinOrderIndex(): Long?

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun getTaskById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskByIdSync(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity)

    // WP-8.11 opus review (FIX): markFired writes ONLY the reminders column, so a counter tap or a
    // sync pull that lands between its read and write is not overwritten with the stale row.
    // `reminders` goes through Converters.fromReminderSpecList (extraJson + key order kept);
    // updatedAt is not touched (D10, app.js:2198).
    @Query("UPDATE tasks SET reminders = :reminders WHERE id = :id")
    suspend fun updateRemindersOnly(id: String, reminders: List<com.example.data.local.entities.ReminderSpec>?): Int

    @Query("INSERT OR REPLACE INTO tombstones (id, at) VALUES (:id, :at)")
    suspend fun insertTombstone(id: String, at: Long)

    @Delete
    suspend fun deleteTaskInternal(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskByIdInternal(id: String)

    // WP-8.2 (§4.4/§5 card: "suspend fun deleteWithTombstone(id: String, at: Long)"). WP-8.23: `at`
    // has no default (clock baseline 0); RpgRepository passes the shared HLC `now()` (review 49,
    // PX-20: Opti `delMark`, app.js:194-201), so a delete always outranks the edit before it.
    @Transaction
    suspend fun deleteWithTombstone(id: String, at: Long) {
        deleteTaskByIdInternal(id)
        insertTombstone(id, at)
    }

    // WP-8.2 (§5 card): habits eligible for a launcher/quick-log shortcut.
    @Query("SELECT * FROM tasks WHERE type = 'habit' AND quickLog IS NOT NULL ORDER BY orderIndex ASC, createdAt DESC")
    fun observeQuickLogHabits(): Flow<List<TaskEntity>>

    // WP-8.2 (§5 card): tasks that carry at least one reminder spec.
    @Query("SELECT * FROM tasks WHERE reminders IS NOT NULL AND reminders != '[]' AND reminders != 'null' ORDER BY orderIndex ASC, createdAt DESC")
    fun observeTasksWithReminders(): Flow<List<TaskEntity>>
}
