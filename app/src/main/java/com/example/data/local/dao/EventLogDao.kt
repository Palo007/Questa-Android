package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.EventLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<EventLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: EventLogEntity)

    @Query("SELECT * FROM event_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun getLogsBetween(from: Long, to: Long): Flow<List<EventLogEntity>>

    // Stage 4 / T4.2 (spec §3 addendum rule 1, evtUploadable's watermark: `e.ts > sinceTs`).
    // One-shot query (mirrors the `...Sync()` naming/suspend convention used by
    // TombstoneDao.getAllTombstonesSync()/DeviceDao.getAllDevicesSync() etc.) for events newer
    // than the last push watermark, used to determine which UTC months were "touched" this push.
    @Query("SELECT * FROM event_logs WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getLogsSinceSync(since: Long): List<EventLogEntity>

    // WP-8.22-B (review 44 item 2): Settings "Waiting to upload" -- how many rows
    // EvtUploadPolicy.uploadable(e, dev, since) would pick, as one COUNT (no rows loaded).
    // Must stay equal to EvtUploadPolicy.ownMonthRecord + `ts > since` (UploadBacklogCountTest).
    @Query(
        "SELECT COUNT(*) FROM event_logs WHERE timestamp > :since AND uid IS NOT NULL AND uid != '' " +
            "AND dev = :dev AND synthetic = 0 AND (imported IS NULL OR imported = 0 OR republish = 1) " +
            "AND kind != 'lifecycle'"
    )
    suspend fun countUploadableSince(since: Long, dev: String): Int

    // Stage 4 / T4.2 (spec §3 addendum rule 21, evtOwnMonthRecords: full-month-range refetch, no
    // watermark filter, used to rebuild a touched month's file from scratch every push).
    @Query("SELECT * FROM event_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getLogsInRangeSync(from: Long, to: Long): List<EventLogEntity>

    // Stage 4 / T4.3 (spec §3 addendum rule 18, the "existingUidSet" half of the two-layer pull
    // dedup: events already present locally for the pulled file's UTC month range).
    @Query("SELECT uid FROM event_logs WHERE timestamp BETWEEN :from AND :to AND uid IS NOT NULL")
    suspend fun getUidsInRangeSync(from: Long, to: Long): List<String>

    // CR-KT-013 (Opti pruneEvents, app.js:2043-2074). All kinds, no filter.
    // Age pass: Opti's ts-index cursor over IDBKeyRange.upperBound(cutoff, true) -- strict `<`.
    @Query("DELETE FROM event_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM event_logs")
    suspend fun countAll(): Int

    // CR-KT-014: the prune-warning window (oldest event vs the age cutoff). null = empty table.
    @Query("SELECT MIN(timestamp) FROM event_logs")
    suspend fun oldestTimestamp(): Long?

    // CR-KT-014 batching: the oldest row not covered by the current age warning (ts >= boundary).
    @Query("SELECT MIN(timestamp) FROM event_logs WHERE timestamp >= :from")
    suspend fun oldestTimestampFrom(from: Long): Long?

    // CR-KT-014: rows that existed at a cap warning (ids are insert order).
    @Query("SELECT MAX(id) FROM event_logs")
    suspend fun maxId(): Long?

    // CR-KT-014: the cap pass bounded to warned rows (id <= :maxId); same order as deleteOldest.
    @Query("DELETE FROM event_logs WHERE id IN (SELECT id FROM event_logs WHERE id <= :maxId ORDER BY timestamp ASC, id ASC LIMIT :n)")
    suspend fun deleteOldestUpTo(n: Int, maxId: Long): Int

    // Cap pass: Opti walks the "ts" index from the start; IDB orders equal index keys by primary
    // key ("id", autoIncrement = insert order), so equal ts -> lower id first.
    @Query("DELETE FROM event_logs WHERE id IN (SELECT id FROM event_logs ORDER BY timestamp ASC, id ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int): Int
}
