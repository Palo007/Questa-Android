package com.example.data

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.SyncDataStore
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.ZoneId

/**
 * WP-8.6 (D17, PX-58) -- launcher taps that could not be applied yet (the boot gate was closed,
 * or the apply failed). Persisted in PENDING_TAPS (§4.3) as a JSON list.
 *
 * Drained on [enqueue], when the BootGate opens (§5.0 B3, via `BootGate.bindTapDrain`) and at the
 * end of every settled round (`SyncManager.tapQueue`).
 * - keeps GATED;
 * - keeps ERROR with `attempts + 1`, and drops it after [MAX_ATTEMPTS] with a synthetic
 *   diagnostic event `tapError`;
 * - drops APPLIED / EVENT_ONLY / UNKNOWN / ALREADY_APPLIED (m5).
 */
class LocalTapQueue(
    private val ds: SyncDataStore,
    private val repo: RpgRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val physicalNow: () -> Long = { com.example.data.clock.PhysicalClock.nowMs() }, // clock-ok:tap-physical
    /** Test seam: the apply step (default [applyHabitTap]). */
    private val apply: suspend (TapRecord, Long) -> TapResult = { r, n -> repo.applyHabitTap(r, n, zone()) },
) {
    @Serializable
    internal data class Pending(val rec: TapRecord, val attempts: Int = 0)

    private val drainLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(Pending.serializer())

    private fun decode(raw: String?): List<Pending> =
        if (raw.isNullOrEmpty()) emptyList() else try { json.decodeFromString(listSer, raw) } catch (e: Exception) { emptyList() }

    private fun encode(list: List<Pending>): String? = if (list.isEmpty()) null else json.encodeToString(listSer, list)

    /** Queues [rec] (once per id) and drains at once. Returns [rec]'s outcome of that drain. */
    suspend fun enqueue(rec: TapRecord, nowMs: Long = physicalNow()): TapResult {
        ds.updatePendingTaps { raw ->
            val cur = decode(raw)
            if (cur.any { it.rec.id == rec.id }) raw else encode(cur + Pending(rec))
        }
        return drain(nowMs).lastOrNull { it.first.id == rec.id }?.second ?: TapResult.ALREADY_APPLIED
    }

    /** Applies every pending tap once, in queue order. */
    suspend fun drain(nowMs: Long): List<Pair<TapRecord, TapResult>> = drainLock.withLock {
        val snapshot = decode(ds.pendingTapsRaw())
        if (snapshot.isEmpty()) return@withLock emptyList()
        val out = ArrayList<Pair<TapRecord, TapResult>>(snapshot.size)
        val next = HashMap<String, Pending?>() // id -> replacement (null = drop)
        for (p in snapshot) {
            val r = apply(p.rec, nowMs)
            out += p.rec to r
            next[p.rec.id] = when (r) {
                TapResult.GATED -> p
                TapResult.ERROR -> {
                    val attempts = p.attempts + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        try {
                            repo.insertLog(EventLogEntity(
                                timestamp = physicalNow(),
                                kind = "tapError",
                                taskId = p.rec.habitId,
                                synthetic = true,
                                payloadJson = buildJsonObject {
                                    put("tapId", JsonPrimitive(p.rec.id))
                                    put("attempts", JsonPrimitive(attempts))
                                }.toString()
                            ))
                        } catch (e: Exception) {
                            // The tap is dropped either way (the queue drops it at MAX_ATTEMPTS),
                            // but a silent drop of a launcher tap is indistinguishable from the
                            // tap never happening, so leave a trace behind for logcat.
                            Log.w(TAG, "could not record tapError for ${p.rec.habitId}; tap dropped", e)
                        }
                        null
                    } else p.copy(attempts = attempts)
                }
                else -> null
            }
        }
        // One edit: taps enqueued while this drain ran are kept as they are.
        ds.updatePendingTaps { raw ->
            val cur = decode(raw)
            encode(cur.mapNotNull { c -> if (next.containsKey(c.rec.id)) next[c.rec.id] else c })
        }
        out
    }

    suspend fun pending(): List<TapRecord> = decode(ds.pendingTapsRaw()).map { it.rec }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val TAG = "LocalTapQueue"
    }
}
