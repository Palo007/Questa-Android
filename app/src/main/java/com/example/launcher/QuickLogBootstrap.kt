package com.example.launcher

import android.app.Application
import android.util.Log
import com.example.QuestaApplication
import com.example.data.clock.StartupBarrier
import com.example.data.inbox.QuickLogHabit
import com.example.data.inbox.QuickLogMeta
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.AuthState
import com.example.reminders.ReminderRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * WP-8.13 (PX-79, PX-80): Bubblewrap `quicklog/QuickLogBootstrap.kt` `onAppStart`, called from
 * `Application.onCreate` (8.I patch I-2) on every process start, including a QuickLogActivity cold
 * start. It must never break the app launch.
 *
 * Order: [StartupBarrier.await] (R15), publish the menu (a fresh install gets "Connect Dropbox"),
 * start the Room observer that republishes on every habit or auth change (PX-80: replaces
 * Bubblewrap's 6 h `ShortcutRefreshWorker`, O4), re-arm the reminders, then enqueue a sync when
 * linked and taps are waiting (Bubblewrap: `if (InboxStore.count() > 0) InboxUploadWorker.enqueue`).
 */
object QuickLogBootstrap {

    class Deps(
        val habits: Flow<List<QuickLogHabit>>,
        val auth: Flow<AuthState>,
        val publish: suspend (AuthState, List<QuickLogHabit>?) -> Unit,
        val rearm: suspend () -> Unit,
        val hasPendingTaps: suspend () -> Boolean,
        val enqueueSync: () -> Unit,
        val awaitBarrier: suspend () -> Unit = { StartupBarrier.await() },
    )

    fun defaultDeps(app: Application): Deps {
        val qa = app as QuestaApplication
        val dao = AppDatabase.getDatabase(app).taskDao()
        return Deps(
            habits = dao.getAllTasks().map { QuickLogMeta.habits(it) },
            auth = qa.syncDataStore.authState,
            publish = { auth, habits -> ShortcutPublisher.publish(app, auth, habits) },
            rearm = { ReminderRuntime.provider(app).rearmAll() },
            hasPendingTaps = { qa.syncDataStore.pendingTapsRaw().let { !it.isNullOrEmpty() && it != "[]" } },
            enqueueSync = { QuickLog.enqueueSync(app) },
        )
    }

    /** Launches [onAppCreate] in [scope]. */
    fun start(app: Application, scope: CoroutineScope, deps: () -> Deps = { defaultDeps(app) }): Job =
        scope.launch { onAppCreate(app, deps) }

    /** Suspends for the process lifetime (the observer). Never throws, except for cancellation. */
    suspend fun onAppCreate(app: Application, deps: () -> Deps = { defaultDeps(app) }) {
        val d = try { deps() } catch (e: Exception) { Log.w(TAG, "bootstrap deps failed", e); return }
        step("barrier") { d.awaitBarrier() }
        var last: Pair<AuthState, List<QuickLogHabit>>? = null
        step("publish") {
            val now = d.auth.first() to d.habits.first()
            last = now
            d.publish(now.first, now.second)
        }
        step("rearm") { d.rearm() }
        step("sync") { if (d.auth.first() == AuthState.LINKED && d.hasPendingTaps()) d.enqueueSync() }
        // The observer's first value is the state just published: skipped, not republished.
        step("observer") {
            combine(d.auth, d.habits) { a, h -> a to h }
                .distinctUntilChanged()
                .collect { s ->
                    if (s != last) {
                        last = s
                        step("republish") { d.publish(s.first, s.second) }
                    }
                }
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap $name failed", e)
        }
    }

    private const val TAG = "QuestaQuickLog"
}
