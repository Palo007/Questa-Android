package com.example.ui

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.boot.BootGate
import com.example.data.clock.StartupBarrier
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.RoundDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")

/** Local wall time in [ZONE]; 2026-09-25 is a Friday. */
internal fun at(d: Int, h: Int, mi: Int = 0, mo: Int = 9): Long =
    LocalDateTime.of(2026, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli()

/**
 * WP-8.10 harness for the §5.0 boot/RYA tests: a real BootGate (single-threaded gate scope) and
 * its real dayMutex, an in-memory Room database, the real RpgRepository and real MainViewModels
 * with an injected process lifecycle, and a fake clock ([now]) in a fixed zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class RyaTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var dev: RoundDevice
    protected val repo get() = dev.repository
    @Volatile protected var now: Long = at(25, 9)
    private val gateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    protected val rolled = Channel<Unit>(Channel.UNLIMITED)
    private val stores = mutableListOf<ViewModelStore>()
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
    protected val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(owner)

    @Before fun ryaSetUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StartupBarrier.markDone()
        dev = RoundDevice(context, "dev-rya")
        dev.syncDataStore.saveLocalDeviceId("dev-rya")
        BootGate.resetForTest(gateScope, { now }, ZONE)
        bindRealStartDay()
        registry.currentState = Lifecycle.State.STARTED
    }

    @After fun ryaTearDown() {
        stores.forEach { it.clear() }
        watchScope.cancel()
        gateScope.cancel()
        dev.close()
        Dispatchers.resetMain()
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { System.currentTimeMillis() })
    }

    /** The §5.0 W1 step 3 bind, plus a completion signal for the test. */
    protected fun bindRealStartDay() {
        val body = repo.startDayBinding()
        BootGate.bindStartDay { body(); rolled.trySend(Unit) }
    }

    protected suspend fun awaitRolled() = withTimeout(10_000) { rolled.receive() }

    /** Waits for every dayMutex holder queued so far (the Mutex is fair). */
    protected suspend fun awaitDayMutex() = BootGate.dayMutex.withLock { }

    protected fun newVm(lifecycle: Lifecycle? = registry): MainViewModel {
        val store = ViewModelStore().also { stores += it }
        val f = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(repo, dev.manager, dev.syncDataStore, lifecycle) as T
        }
        return ViewModelProvider(store, f)[MainViewModel::class.java]
    }

    protected fun clearVm(index: Int) = stores[index].clear()

    /** Counts null -> non-null transitions of [vm]'s sheet ("RYA N×"). */
    protected fun countOpens(vm: MainViewModel): AtomicInteger {
        val n = AtomicInteger()
        var prev: RyaSheetState? = null
        vm.rya.onEach { s -> if (prev == null && s != null) n.incrementAndGet(); prev = s }.launchIn(watchScope)
        return n
    }

    /** The watcher runs on the emitting thread; give it a moment before reading the count. */
    protected suspend fun AtomicInteger.settled(): Int { kotlinx.coroutines.delay(150); return get() }
    protected suspend fun <T> MutableList<T>.settled(): List<T> { kotlinx.coroutines.delay(150); return toList() }

    protected fun collectToasts(vm: MainViewModel): MutableList<String> {
        val out = CopyOnWriteArrayList<String>()
        vm.toasts.onEach { out += it }.launchIn(watchScope)
        return out
    }

    /** The production ON_START order: the W1 step 6 observer (B7) first, then the VM observer. */
    protected suspend fun onStart() {
        BootGate.onForeground(now)
        fireVmOnStart()
    }

    protected fun fireVmOnStart() {
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
    }

    protected suspend fun seedChar(lastCron: String = "20260924") = repo.insertCharacter(CharacterEntity(lastCron = lastCron, updatedAt = com.example.data.clock.PhysicalClock.nowMs()))

    protected suspend fun seedDaily(id: String, done: Boolean = false, title: String = "Daily $id", orderIndex: Long = 0) =
        repo.insertTask(TaskEntity(id = id, type = "daily", title = title, done = done, streak = 0, orderIndex = orderIndex, updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))

    protected suspend fun char() = repo.getCharacterSync()
    protected suspend fun task(id: String) = repo.getTaskByIdSync(id)
    protected suspend fun cronRuns() = repo.allLogs.first().count { it.kind == "cron" }
    protected suspend fun missLogs() = repo.allLogs.first().count { it.kind == "miss" }
    protected suspend fun lateCredits() = repo.allLogs.first().count { it.kind == "complete" && it.payloadJson?.contains("\"late\":true") == true }

    /** Seeds [ids] as missed dailies, cold-starts not-configured, and returns the open sheet. */
    protected suspend fun openSheet(vm: MainViewModel, vararg ids: String): RyaSheetState {
        seedChar()
        ids.forEachIndexed { i, id -> seedDaily(id, orderIndex = i.toLong()) }
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled()
        return vm.rya.value ?: error("no sheet")
    }

    protected fun src(rel: String): String {
        val f = java.io.File("src/main/java/com/example/$rel").let { if (it.isFile) it else java.io.File("app/src/main/java/com/example/$rel") }
        return f.readText()
    }
}
