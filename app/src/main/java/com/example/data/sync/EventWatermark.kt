package com.example.data.sync

import kotlinx.coroutines.flow.first

/**
 * WP-8.6 (§4.3 EVT_LAST_UPLOAD_TS) -- Opti `cfg.evtLastUploadTs`. 8.7 advances it after an event
 * push; 8.8 lowers it to `rec.ts - 1` so an absorbed inbox tap is pushed again (sync.js:575-650).
 * Both moves are one DataStore edit each, so they never race each other into a wrong value.
 */
interface EventWatermark {
    suspend fun get(): Long
    /** Raises the watermark to [ts]; a lower [ts] is ignored. */
    suspend fun advanceTo(ts: Long)
    /** Lowers the watermark to [ts]; a higher [ts] is ignored. */
    suspend fun lowerTo(ts: Long)
}

class DataStoreEventWatermark(private val ds: SyncDataStore) : EventWatermark {
    override suspend fun get(): Long = ds.evtLastUploadTs.first()
    override suspend fun advanceTo(ts: Long) = ds.advanceEvtLastUploadTs(ts)
    override suspend fun lowerTo(ts: Long) = ds.lowerEvtLastUploadTs(ts)
}
