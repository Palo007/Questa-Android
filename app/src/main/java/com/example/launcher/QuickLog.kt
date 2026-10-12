package com.example.launcher

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.QuestaApplication
import com.example.data.LocalTapQueue
import com.example.data.TapRecord
import com.example.data.TapResult
import com.example.data.sync.SyncWorker
import com.example.data.sync.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WP-8.13 (PX-78): the pure part of Bubblewrap `QuickLogActivity.onCreate` (QuickLogActivity.kt:16-45).
 *
 * Kotlin applies the tap through [LocalTapQueue.enqueue] (m-N5) instead of writing an inbox file.
 * The toast map covers all 6 [TapResult]s:
 * - APPLIED, ALREADY_APPLIED, GATED: "logged" per [AuthState], as Bubblewrap (a queued tap reads
 *   as logged, like the Bubblewrap queue);
 * - EVENT_ONLY: "Late log saved to history · $title" (Opti app.js:2456);
 * - UNKNOWN, ERROR or an exception: "Could not save the log for $title" (ERROR stays queued, m5).
 */
object QuickLog {
    const val SRC = "android-shortcut"
    const val NO_HABIT = "Quick log: this item has no habit. Remove it and add it again."
    const val LOGGED_CONNECT = "Logged. Connect Dropbox to sync."
    const val LOGGED_RECONNECT = "Logged. Reconnect Dropbox to sync."

    fun logged(title: String, dir: Int): String = if (dir == -1) "$title −1 logged ✓" else "$title +1 logged ✓"
    fun late(title: String): String = "Late log saved to history · $title"
    fun failed(title: String): String = "Could not save the log for $title"

    /** The toast for [r] (null = the apply threw). */
    fun message(r: TapResult?, auth: AuthState, title: String, dir: Int): String = when (r) {
        TapResult.APPLIED, TapResult.ALREADY_APPLIED, TapResult.GATED -> when (auth) {
            AuthState.NOT_LINKED -> LOGGED_CONNECT
            AuthState.RECONNECT_REQUIRED -> LOGGED_RECONNECT
            AuthState.LINKED -> logged(title, dir)
        }
        TapResult.EVENT_ONLY -> late(title)
        TapResult.UNKNOWN, TapResult.ERROR, null -> failed(title)
    }

    /** Bubblewrap `InboxRecord.create`: the JavaScript `getTimezoneOffset()` sign. */
    fun tzOffsetMin(tz: TimeZone, nowMs: Long): Int = -tz.getOffset(nowMs) / 60000

    /** Bubblewrap QuickLogActivity.kt:19-20: a blank title reads "Habit"; any dir other than -1 is +1. */
    fun normTitle(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: "Habit"
    fun normDir(raw: Int): Int = if (raw == -1) -1 else 1

    /**
     * One launcher tap. [tsMs] is the PHYSICAL tap time (D23), taken by the caller. Returns the
     * toast text. Never throws (a cancellation is rethrown).
     */
    suspend fun handleTap(habitId: String?, title: String, dir: Int, tsMs: Long, tz: TimeZone, deps: QuickLogDeps): String {
        if (habitId.isNullOrEmpty()) return NO_HABIT
        return try {
            withContext(deps.io) {
                val rec = TapRecord(
                    id = deps.newId(),
                    habitId = habitId,
                    dir = dir,
                    ts = tsMs,
                    tzOffsetMin = tzOffsetMin(tz, tsMs),
                    src = SRC,
                )
                val r = deps.enqueue(rec)
                val auth = try { deps.authState() } catch (e: CancellationException) { throw e } catch (e: Exception) { AuthState.NOT_LINKED }
                try { deps.afterTap(r, auth) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                message(r, auth, title, dir)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(title)
        }
    }

    /**
     * Bubblewrap `InboxUploadWorker.enqueue` (InboxUploadWorker.kt:78-86): one network-constrained
     * round so a tap reaches Dropbox even if the process dies. The round itself is gated as every
     * round is (the interlock is the first line of `syncNowUnchecked`).
     */
    fun enqueueSync(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        } catch (e: Exception) {
            // Best effort: the scheduler's own triggers and the periodic worker still run.
        }
    }

    const val SYNC_WORK_NAME = "questa-quicklog-sync"
}

/** The seams [QuickLog.handleTap] needs. */
class QuickLogDeps(
    val enqueue: suspend (TapRecord) -> TapResult,
    val authState: suspend () -> AuthState,
    /** After the apply: a sync is enqueued while LINKED (Bubblewrap enqueues its upload worker). */
    val afterTap: (TapResult, AuthState) -> Unit = { _, _ -> },
    val io: CoroutineDispatcher = Dispatchers.IO,
    val newId: () -> String = { UUID.randomUUID().toString() },
)

/**
 * The process-wide tap queue and the deps provider. 8.I binds the Application's queue through
 * [bindTapQueue] (see WP-8.13_NOTES.md patch I-2), so launcher taps and the BootGate drain share one
 * drain lock. Until then the first tap builds a queue over the same DataStore and repository (the
 * duplicate check in `applyHabitTap` is transactional, so two queues can never score a tap twice).
 */
object QuickLogRuntime {
    @Volatile private var tapQueue: LocalTapQueue? = null

    fun bindTapQueue(q: LocalTapQueue) { tapQueue = q }

    /**
     * App-lifetime scope for a tap (review 34 item 1): a recreate or destroy of QuickLogActivity
     * never cancels an apply half-way or loses its toast and afterTap. Main for the toast;
     * [QuickLog.handleTap] moves the apply to [QuickLogDeps.io].
     */
    @Volatile var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun queue(app: QuestaApplication): LocalTapQueue =
        tapQueue ?: synchronized(this) {
            tapQueue ?: LocalTapQueue(app.syncDataStore, app.repository).also { tapQueue = it }
        }

    /** Overridable in tests. */
    @Volatile var provider: (Application) -> QuickLogDeps = { app ->
        val qa = app as QuestaApplication
        QuickLogDeps(
            enqueue = { queue(qa).enqueue(it) },
            authState = { qa.syncDataStore.authState.first() },
            afterTap = { r, auth ->
                if (auth == AuthState.LINKED && r != TapResult.UNKNOWN) QuickLog.enqueueSync(qa)
            },
        )
    }

    internal fun resetForTest() {
        tapQueue = null
    }
}
