package com.example.reminders

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.dao.TaskDao

/**
 * WP-8.11: the one write reminders make. Opti `checkReminders` sets `r.lastFiredKey` and calls
 * `save()` with NO `updatedAt` bump (app.js:2198, D10), so the task row keeps its `updatedAt`
 * and only `reminders[idx].lastFiredKey` changes.
 *
 * opus review (FIX): the read-modify-write of the reminders JSON runs in one Room transaction,
 * and the write touches ONLY the reminders column. A counter tap or a sync pull between the read
 * and the write is kept (it used to be overwritten by the stale row), and so is a concurrent
 * reminders edit (transactions are serialized).
 */
class ReminderRepository(private val db: AppDatabase, private val dao: TaskDao = db.taskDao()) {

    /** Returns true when the reminders column was rewritten. A missing task or index is a no-op. */
    suspend fun markFired(taskId: String, idx: Int, fireKey: String): Boolean = db.withTransaction {
        val list = dao.getTaskByIdSync(taskId)?.reminders ?: return@withTransaction false
        if (idx !in list.indices) return@withTransaction false
        if (list[idx].lastFiredKey == fireKey) return@withTransaction false
        val updated = list.toMutableList()
        updated[idx] = list[idx].copy(lastFiredKey = fireKey)
        // updatedAt unchanged on purpose (D10); the converter keeps extraJson and key order.
        dao.updateRemindersOnly(taskId, updated) > 0
    }
}
