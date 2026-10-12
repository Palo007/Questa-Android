package com.example.data.local

import androidx.room.withTransaction
import com.example.data.local.dao.EventLogDao
import com.example.data.sync.events.EvtUploadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CR-KT-013: local event pruning, a copy of Opti `pruneEvents` (app.js:2043-2074) as confirmed in
 * RE-PWA-011. Golden fixture: shared\fixtures\CR-KT-013\prune.json.
 *
 * 1. Age: delete every event (all kinds, synthetic and diagnostic too) with ts < now - ageLimitMs.
 *    ts == cutoff is kept.
 * 2. Cap: AFTER step 1 has committed, count; if count > cap, delete the oldest (count - cap) by
 *    ts, equal ts -> lower id (insert order) first. Count and delete share one transaction, as
 *    Opti's tx2.
 *
 * No side effects: no tombstone, no sync write, no log event. Limits are copied, not changed
 * ([DEFERRED_OPEN_QUESTION] per CR-KT-013 item 7). [prune] throws on error; the caller
 * ([EventPruneTrigger]) swallows. If step 1 fails, step 2 does not run (Opti: an aborted tx1 never
 * fires tx.oncomplete).
 */
class EventPruner(
    private val db: AppDatabase,
    private val now: () -> Long,
    private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS, // == Opti EVENT_AGE_LIMIT_MS, app.js:986
    private val cap: Int = EVENT_HARD_CAP,
    /** Debug clock offset (DebugClockOffset.offsetMs). Non-zero -> skip: a shifted clock must not delete real events. */
    private val clockOffsetMs: () -> Long = { 0L },
    private val dao: EventLogDao = db.eventLogDao(),
) {
    /** The CR-KT-013 rule: age cutoff now - ageLimitMs, then the cap pass. Returns deleted rows (0 when skipped). */
    suspend fun prune(): Int {
        if (clockOffsetMs() != 0L) return 0
        val byAge = dao.deleteOlderThan(now() - ageLimitMs)
        val byCap = db.withTransaction {
            val over = dao.countAll() - cap
            if (over > 0) dao.deleteOldest(over) else 0
        }
        return byAge + byCap
    }

    /**
     * CR-KT-014: the same passes, bounded by a warning. [ageCutoff] null = no age pass;
     * [capMaxId] null = no cap pass, else the cap pass picks only rows with id <= capMaxId.
     */
    suspend fun prune(ageCutoff: Long?, capMaxId: Long?): Int {
        if (clockOffsetMs() != 0L) return 0
        val byAge = if (ageCutoff != null) dao.deleteOlderThan(ageCutoff) else 0
        if (capMaxId == null) return byAge
        val byCap = db.withTransaction {
            val over = dao.countAll() - cap
            if (over > 0) dao.deleteOldestUpTo(over, capMaxId) else 0
        }
        return byAge + byCap
    }

    companion object {
        /** Opti `EVENT_HARD_CAP` (app.js:987). */
        const val EVENT_HARD_CAP: Int = 200_000
    }
}

/**
 * Once per app session (Opti `schedulePrune`, app.js:2039, flag `_idbPruned`): the first call
 * launches [prune] on [scope]; later calls do nothing and return null. Best effort: every error is
 * swallowed. One instance lives in the Application, so "once per instance" = once per process.
 */
class EventPruneTrigger {
    private val scheduled = AtomicBoolean(false)

    fun scheduleOnce(scope: CoroutineScope, prune: suspend () -> Unit): Job? {
        if (!scheduled.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                prune()
            } catch (e: Exception) {
                // Best effort, as Opti (errors swallowed). A CancellationException is passed on
                // only when THIS job is cancelled; Room throws one from a closed database, and
                // that is an error to swallow.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
            }
        }
    }
}
