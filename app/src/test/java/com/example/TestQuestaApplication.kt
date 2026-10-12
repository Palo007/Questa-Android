package com.example

import com.example.data.boot.BootGate
import com.example.data.clock.ProcessHlc
import com.example.data.clock.StartupBarrier
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Robolectric application for every test (selected by `robolectric.properties`; tests with
 * their own `@Config(application = ...)` are not affected).
 *
 * WP-8.I (W7 close): every test gets a fresh process clock ([ProcessHlc.build]) over its own store.
 *
 * Stage 8 test-isolation (quiet boot): the real [QuestaApplication.onCreate] still builds every
 * field (repository, syncManager, forceOps, sectionImporter, ...), but by default NONE of its
 * background work runs: StartupMigrations, the BootGate rollover (startDay / runCron), the
 * reminder observers, QuickLogBootstrap, the SyncScheduler triggers and the event-log writer all
 * launch into a cancelled scope. Before, that work wrote the shared `Context.syncDataStore` file
 * while the test wrote it too (on Windows: "Unable to rename ...preferences_pb.tmp"), and the
 * scheduler ran rounds through the test's fake `DropboxService.client`.
 *
 * Quiet boot opens [StartupBarrier] itself: the first real boot in the JVM used to open this
 * one-shot within milliseconds, so every later test already saw it open.
 *
 * A test that needs the real boot opts in with
 * `@Config(application = BootingTestQuestaApplication::class)`.
 *
 * Every boot (quiet or real) first stops the previous test's app: its application scope, its
 * process clock's persist loop and the BootGate scope are cancelled and joined.
 */
open class TestQuestaApplication : QuestaApplication() {

    /** True: run the app's real background startup work (see [BootingTestQuestaApplication]). */
    protected open val runsBackgroundBoot: Boolean get() = false

    private var clockScope: CoroutineScope? = null

    /** Never the builder's real key: every test starts with no stored app key. */
    override val appKeySeed: String get() = ""

    override fun onCreate() {
        quiescePrevious()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        clockScope = scope
        ProcessHlc.setForTest(isolatedClock(SyncDataStore(this), scope))
        if (runsBackgroundBoot) {
            BootGate.scope = liveBootGateScope()
            super.onCreate()
        } else {
            StartupBarrier.markDone()
            setApplicationScope(inertScope())
            BootGate.scope = inertScope() // the boot rollover launched in onCreate never runs
            super.onCreate()
            BootGate.scope = liveBootGateScope() // later explicit BootGate calls still work
        }
        previous = this
    }

    private fun isolatedClock(store: SyncDataStore, scope: CoroutineScope) = ProcessHlc.build(
        seed = try { runBlocking { store.getHlcLastIssued() } } catch (e: Exception) { 0L },
        scope = scope,
    ) { store.saveHlcLastIssued(it) }

    private fun setApplicationScope(scope: CoroutineScope) {
        APP_SCOPE_FIELD.set(this, scope)
    }

    private fun applicationScopeJob(): Job? = (APP_SCOPE_FIELD.get(this) as CoroutineScope).coroutineContext[Job]

    companion object {
        private val APP_SCOPE_FIELD = QuestaApplication::class.java.getDeclaredField("applicationScope")
            .apply { isAccessible = true }

        /** The app of the previous test in this sandbox (static state survives between tests). */
        @Volatile private var previous: TestQuestaApplication? = null

        private fun inertScope() = CoroutineScope(Job().apply { cancel() })

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun liveBootGateScope() =
            CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

        /** Cancels and joins everything the previous test's app (and BootGate) still runs. */
        private fun quiescePrevious() = runBlocking {
            val prev = previous
            previous = null
            withTimeoutOrNull(10_000) {
                prev?.applicationScopeJob()?.cancelAndJoin()
                prev?.clockScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            }
            // Cancel only: a test may have left a TestScope here whose scheduler never runs again.
            BootGate.scope.coroutineContext[Job]?.cancel()
            withTimeoutOrNull(2_000) { // a cancelled startDay still finishes its critical section
                BootGate.dayMutex.lock()
                BootGate.dayMutex.unlock()
            }
        }
    }
}

/** Opt-in: the real background boot (StartupMigrations, BootGate rollover, observers, scheduler). */
class BootingTestQuestaApplication : TestQuestaApplication() {
    override val runsBackgroundBoot: Boolean get() = true
}
