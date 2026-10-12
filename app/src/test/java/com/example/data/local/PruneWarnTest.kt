package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.events.EvtUploadPolicy
import com.example.ui.settings.OPEN_SETTINGS_BACKUP
import com.example.ui.settings.SettingsRoute
import com.example.ui.settings.settingsRouteForExtra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** In-memory [PruneWarnStore] (same pure state functions as the DataStore one). */
class FakePruneWarnStore(private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS) : PruneWarnStore {
    val state = MutableStateFlow(PruneWarnState())
    var writes = 0
    override suspend fun read(): PruneWarnState = state.value
    override suspend fun write(s: PruneWarnState) { writes++; state.value = s }
    override suspend fun markBannerShown(now: Long) { writes++; state.value = state.value.bannerShown(now, ageLimitMs) }
    override suspend fun dismissBanner() { writes++; state.value = state.value.copy(bannerVisible = false) }
    override fun banner(): Flow<PruneBanner?> = state.map { it.banner() }
}

/** Records warnings (with the time they were given); [shown] = notification posted. */
class RecordingWarner(private val clock: () -> Long, var shown: Boolean = true) : PruneWarner {
    data class Given(val at: Long, val notice: PruneNotice)
    val calls = mutableListOf<Given>()
    override suspend fun warn(notice: PruneNotice): Boolean { calls += Given(clock(), notice); return shown }
}

/**
 * CR-KT-014 (DIVERGENCE, Kotlin-only), batched warnings (user decision 2026-10-08).
 * Invariant: a row is deleted only if a warning seen >= 14 days earlier covers it (ts < boundary),
 * and never before it is EVENT_AGE_LIMIT_MS old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PruneWarnTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private val day = 86_400_000L
    private val age = EvtUploadPolicy.EVENT_AGE_LIMIT_MS
    private val t0 = 1_790_000_000_000L
    private val ts = HashMap<Long, Long>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    // ---- policy ----

    @Test fun constants() {
        assertEquals(14L * day, PruneWarnPolicy.WARN_LEAD_MS)
        assertEquals(14L * day, PruneWarnPolicy.GRACE_MS)
        assertEquals(30L * day, PruneWarnPolicy.BATCH_MS)
        assertEquals(190_000, PruneWarnPolicy.WARN_COUNT)
    }

    @Test fun window_ageEdge_exactly14DaysBeforeCutoff() {
        val edge = t0 - age + 14L * day
        assertFalse("oldest == edge: closed (strict <)", PruneWarnPolicy.windowOpen(edge, 1, t0))
        assertTrue("oldest == edge - 1: open", PruneWarnPolicy.windowOpen(edge - 1, 1, t0))
    }

    @Test fun window_countEdge_190000() {
        assertFalse(PruneWarnPolicy.windowOpen(t0, 189_999, t0))
        assertTrue(PruneWarnPolicy.windowOpen(t0, 190_000, t0))
    }

    @Test fun window_emptyStore_closed() {
        assertNull(PruneWarnPolicy.kinds(null, 0, t0))
    }

    @Test fun boundary_isWarningPlus44DaysMinusAgeLimit() {
        assertEquals(t0 + 44L * day - age, PruneWarnPolicy.boundaryFor(t0))
    }

    @Test fun ageCutoff_nothingBeforeGrace_thenMinOfTodayAndBoundary_prevStaysInForce() {
        val b = PruneWarnPolicy.boundaryFor(t0)
        val a = AgeWarning(t0, b)
        assertNull(PruneWarnPolicy.ageCutoff(a, t0 + 14L * day - 1))
        assertEquals(t0 + 14L * day - age, PruneWarnPolicy.ageCutoff(a, t0 + 14L * day))
        assertEquals(b, PruneWarnPolicy.ageCutoff(a, t0 + 400L * day))
        val renewed = AgeWarning(t0 + 31L * day, PruneWarnPolicy.boundaryFor(t0 + 31L * day), prevBoundary = b)
        assertEquals("old boundary in force during the new grace", minOf(t0 + 35L * day - age, b),
            PruneWarnPolicy.ageCutoff(renewed, t0 + 35L * day))
    }

    @Test fun text_english() {
        // 1_790_000_000_000 = 2026-09-21T14:13:20Z
        val n = PruneNotice(startAt = t0, boundary = t0 - 365L * day)
        assertEquals("Old activity from before 21 Sep 2025 will be deleted, starting 21 Sep 2026.", PruneWarnText.title(n, ZoneOffset.UTC))
        assertEquals("The oldest activity will be deleted to stay under 200,000 entries, starting 21 Sep 2026.",
            PruneWarnText.title(PruneNotice(t0, null), ZoneOffset.UTC))
        assertEquals(PruneWarnText.title(n, ZoneOffset.UTC), PruneWarnText.bannerTitle(n, t0 - 1, ZoneOffset.UTC))
        assertEquals("Old activity from before 21 Sep 2025 is being deleted.", PruneWarnText.bannerTitle(n, t0, ZoneOffset.UTC))
        assertEquals("Back up now", PruneWarnText.ACTION)
    }

    @Test fun backupExtra_opensBackupRoute() {
        assertEquals(SettingsRoute.Backup, settingsRouteForExtra(OPEN_SETTINGS_BACKUP))
    }

    // ---- guard ----

    private suspend fun insert(id: Long, t: Long) {
        ts[id] = t
        db.eventLogDao().insertLog(EventLogEntity(id = id, uid = "u$id", timestamp = t, kind = "complete"))
    }

    private fun ids(): List<Long> = buildList {
        db.openHelper.readableDatabase.query("SELECT id FROM event_logs ORDER BY id").use { while (it.moveToNext()) add(it.getLong(0)) }
    }

    private fun guard(
        store: PruneWarnStore,
        warner: PruneWarner,
        clock: () -> Long,
        offset: Long = 0L,
        cap: Int = EventPruner.EVENT_HARD_CAP,
        warnCount: Int = PruneWarnPolicy.WARN_COUNT,
        pruneCalls: MutableList<Pair<Long?, Long?>> = mutableListOf(),
    ) = WarnedEventPrune(
        dao = db.eventLogDao(),
        store = store,
        warner = warner,
        now = clock,
        prune = { cutoff, capMaxId ->
            pruneCalls += cutoff to capMaxId
            EventPruner(db, now = clock, cap = cap).prune(cutoff, capMaxId)
        },
        clockOffsetMs = { offset },
        warnCount = warnCount,
    )

    @Test fun emptyStore_noWarning_noWrites() = runBlocking {
        val store = FakePruneWarnStore()
        val warner = RecordingWarner({ t0 })
        assertEquals(WarnedEventPrune.Outcome.IDLE, guard(store, warner, { t0 }).run())
        assertTrue(warner.calls.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test fun recentRowsOnly_noWarning() = runBlocking {
        insert(1, t0 - age + 14L * day) // exactly at the lead edge: not yet
        val store = FakePruneWarnStore()
        val warner = RecordingWarner({ t0 })
        assertEquals(WarnedEventPrune.Outcome.IDLE, guard(store, warner, { t0 }).run())
        assertTrue(warner.calls.isEmpty())
    }

    @Test fun firstRun_withOldEvents_warnsWithBoundary_andSkipsPrune() = runBlocking {
        insert(1, t0 - age - day)
        insert(2, t0)
        val store = FakePruneWarnStore()
        val warner = RecordingWarner({ t0 })
        val calls = mutableListOf<Pair<Long?, Long?>>()
        assertEquals(WarnedEventPrune.Outcome.WARNED, guard(store, warner, { t0 }, pruneCalls = calls).run())
        val b = t0 + 44L * day - age
        assertEquals(listOf(PruneNotice(t0 + 14L * day, b)), warner.calls.map { it.notice })
        assertEquals(AgeWarning(t0, b), store.state.value.age)
        assertTrue(calls.isEmpty())
        assertEquals(listOf(1L, 2L), ids())
    }

    @Test fun grace_noDeleteBefore14Days_thenOnlyAgedCoveredRows() = runBlocking {
        insert(1, t0 - age - day)
        insert(2, t0 - age + 20L * day)                  // covered (ts < boundary), not yet 18 months
        val store = FakePruneWarnStore()
        var now = t0
        val g = guard(store, RecordingWarner({ now }), { now })
        g.run()
        now = t0 + 14L * day - 1
        g.run()
        assertEquals(listOf(1L, 2L), ids())
        now = t0 + 14L * day
        assertEquals(WarnedEventPrune.Outcome.PRUNED, g.run())
        assertEquals("row 2 waits until it is 18 months old", listOf(2L), ids())
        now = t0 + 20L * day
        g.run()
        assertEquals("not yet: ts == cutoff is kept", listOf(2L), ids())
        now = t0 + 20L * day + 1
        g.run()
        assertTrue(ids().isEmpty())
        assertNotNull("warning stays valid until the boundary ages out", store.state.value.age)
    }

    @Test fun boundaryEdge_rowAtBoundaryNotCovered_getsNewWarning() = runBlocking {
        val b = t0 + 44L * day - age
        insert(1, t0 - age - day)                         // triggers the first warning
        insert(2, b - 1)                                  // covered
        insert(3, b)                                      // NOT covered (strict <)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val g = guard(store, warner, { now })
        g.run()
        now = b + age + 1                                 // both rows 2 and 3 are now > 18 months old
        g.run()
        assertEquals("row 2 deleted, row 3 kept", listOf(3L), ids())
        assertEquals(2, warner.calls.size)
        val w2 = store.state.value.age!!
        assertEquals(now, w2.at)
        now += 14L * day
        g.run()
        assertTrue("row 3 goes 14 days after its own warning", ids().isEmpty())
    }

    @Test fun lateOpen_deletesOnlyCoveredRows_restGetANewWarning() = runBlocking {
        val b = t0 + 44L * day - age
        insert(1, t0 - age - 1)
        insert(2, b - 1)
        insert(3, b)
        insert(4, b + 10L * day)
        insert(5, t0)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val calls = mutableListOf<Pair<Long?, Long?>>()
        val g = guard(store, warner, { now }, pruneCalls = calls)
        g.run()
        now = t0 + 60L * day
        assertEquals(WarnedEventPrune.Outcome.PRUNED, g.run())
        assertEquals(listOf<Pair<Long?, Long?>>(b to null), calls)
        assertEquals(listOf(3L, 4L, 5L), ids())
        assertEquals(2, warner.calls.size)
    }

    @Test fun clockJumpForward_keepsBoundary() = runBlocking {
        val b = t0 + 44L * day - age
        insert(1, t0 - age - 1)
        insert(2, b)
        val store = FakePruneWarnStore()
        var now = t0
        val g = guard(store, RecordingWarner({ now }), { now })
        g.run()
        now = t0 + 400L * day
        g.run()
        assertEquals(listOf(2L), ids())
    }

    @Test fun countWarning_doesNotLicenseAgeDeletes() = runBlocking {
        insert(1, t0 - 3); insert(2, t0 - 2); insert(3, t0 - 1)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val calls = mutableListOf<Pair<Long?, Long?>>()
        val g = guard(store, warner, { now }, cap = 10, warnCount = 3, pruneCalls = calls)
        g.run()
        assertEquals(CapWarning(t0, 3L), store.state.value.cap)
        assertNull(store.state.value.age)
        insert(4, t0 - age - 5L * day)                    // an old row arrives later (import)
        now = t0 + 15L * day
        assertEquals(WarnedEventPrune.Outcome.PRUNED, g.run())
        assertEquals("cap pass only", listOf<Pair<Long?, Long?>>(null to 3L), calls)
        assertEquals(listOf(1L, 2L, 3L, 4L), ids())
        assertEquals("the old row gets its own warning", now, store.state.value.age!!.at)
    }

    @Test fun ageOnlyWarning_capPassSkipped() = runBlocking {
        insert(1, t0 - age - 1); insert(2, t0 - 2); insert(3, t0 - 1)
        val store = FakePruneWarnStore()
        var now = t0
        val g = guard(store, RecordingWarner({ now }), { now }, cap = 1, warnCount = 100)
        g.run()
        assertNull(store.state.value.cap)
        now = t0 + 14L * day
        g.run()
        assertEquals("count 2 > cap 1, but the cap was never warned", listOf(2L, 3L), ids())
    }

    @Test fun capPass_deletesOnlyRowsThatExistedAtTheWarning_thenCapWarningCleared() = runBlocking {
        insert(1, t0 - 3); insert(2, t0 - 2); insert(3, t0 - 1)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val g = guard(store, warner, { now }, cap = 3, warnCount = 3)
        g.run()
        for (id in 4L..8L) insert(id, t0 - 100 - id)      // big import during the grace
        now = t0 + 14L * day
        g.run()
        assertEquals("only warned rows (id <= 3) go", listOf(4L, 5L, 6L, 7L, 8L), ids())
        assertEquals("one use, then a new cap warning", CapWarning(now, 8L), store.state.value.cap)
        assertEquals(2, warner.calls.size)
    }

    @Test fun eventPruner_boundedCapPass() = runBlocking {
        for (id in 1L..5L) insert(id, t0 - 10 + (5 - id)) // id 5 oldest
        EventPruner(db, now = { t0 }, cap = 2).prune(null, capMaxId = 2)
        assertEquals("over = 3 but only ids <= 2 may go", listOf(3L, 4L, 5L), ids())
    }

    @Test fun debugClockOffset_skipsEverything() = runBlocking {
        insert(1, t0 - age - day)
        val store = FakePruneWarnStore()
        val warner = RecordingWarner({ t0 })
        val calls = mutableListOf<Pair<Long?, Long?>>()
        assertEquals(WarnedEventPrune.Outcome.SKIPPED_OFFSET, guard(store, warner, { t0 }, offset = day, pruneCalls = calls).run())
        assertTrue(warner.calls.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test fun warnerFails_noWarningStored() = runBlocking {
        insert(1, t0 - age - day)
        val store = FakePruneWarnStore()
        val g = guard(store, PruneWarner { throw IllegalStateException("no channel") }, { t0 })
        val job = EventPruneTrigger().scheduleOnce(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { g.run() }!!
        job.join()
        assertEquals(PruneWarnState(), store.state.value)
        assertEquals(listOf(1L), ids())
    }

    @Test fun bannerFallback_graceStartsWhenShown() = runBlocking {
        insert(1, t0 - age - day)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now }, shown = false)
        val g = guard(store, warner, { now })
        assertEquals(WarnedEventPrune.Outcome.WARNED, g.run())
        assertNull(store.state.value.age)
        now = t0 + 30L * day
        assertEquals(WarnedEventPrune.Outcome.AWAITING_BANNER, g.run())
        assertEquals("no re-warn while queued", 1, warner.calls.size)
        assertEquals(listOf(1L), ids())
        store.markBannerShown(t0 + 31L * day)
        assertEquals(AgeWarning(t0 + 31L * day, PruneWarnPolicy.boundaryFor(t0 + 31L * day)), store.state.value.age)
        now = t0 + 45L * day - 1
        g.run()
        assertEquals(listOf(1L), ids())
        now = t0 + 45L * day
        g.run()
        assertTrue(ids().isEmpty())
    }

    @Test fun notice_newCapWarningAfterAgeWarning_usesCapTextOnly() = runBlocking {
        val b = t0 + 44L * day - age
        val st = PruneWarnState(age = AgeWarning(t0, b), cap = CapWarning(t0 + 31L * day, 9L))
        assertEquals(PruneNotice(t0 + 45L * day, null), st.notice())
        assertEquals("newest is age: age text", PruneNotice(t0 + 45L * day, PruneWarnPolicy.boundaryFor(t0 + 31L * day)),
            PruneWarnState(age = AgeWarning(t0 + 31L * day, PruneWarnPolicy.boundaryFor(t0 + 31L * day)), cap = CapWarning(t0, 9L)).notice())

        // Through the gate: age warning first, a cap warning later -> the cap notice has no boundary.
        insert(1, t0 - age - day); insert(2, t0)
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val g = guard(store, warner, { now }, warnCount = 4)
        g.run()
        insert(3, t0 + 1); insert(4, t0 + 2)
        now = t0 + 5L * day
        g.run()
        assertEquals(2, warner.calls.size)
        assertEquals(PruneNotice(now + 14L * day, null), warner.calls[1].notice)
    }

    @Test fun queuedBanner_droppedWhenItsReasonIsGone() = runBlocking {
        insert(1, t0 - age - day)
        val store = FakePruneWarnStore()
        var now = t0
        val g = guard(store, RecordingWarner({ now }, shown = false), { now })
        g.run()
        assertNotNull(store.state.value.pending)
        db.openHelper.writableDatabase.execSQL("DELETE FROM event_logs") // e.g. a reset
        now = t0 + day
        assertEquals(WarnedEventPrune.Outcome.IDLE, g.run())
        assertNull(store.state.value.pending)
        assertNull(store.state.value.banner())
    }

    // ---- monthly cadence over 120 days ----

    @Test fun simulation_120DailyOpens_monthlyWarnings_invariantHolds() = runBlocking {
        var id = 0L
        // 600 days of history: 60 days already past the age limit.
        var t = t0 - age - 60L * day
        while (t < t0) { insert(++id, t); t += day }
        val store = FakePruneWarnStore()
        var now = t0
        val warner = RecordingWarner({ now })
        val g = guard(store, warner, { now })
        var deleted = 0
        for (d in 0 until 120) {
            now = t0 + d * day + 8L * 3_600_000L
            insert(++id, now - 3_600_000L)
            val before = ids().toSet()
            g.run()
            val gone = before - ids().toSet()
            for (r in gone) {
                val rowTs = ts.getValue(r)
                assertTrue("row $r deleted before it was 18 months old", rowTs < now - age)
                assertTrue("row $r deleted without a covering warning >= 14 days old",
                    warner.calls.any { it.at <= now - PruneWarnPolicy.GRACE_MS && it.notice.boundary != null && rowTs < it.notice.boundary!! })
            }
            deleted += gone.size
        }
        assertEquals("about one warning per 30 days", 4, warner.calls.size)
        assertTrue("old rows were deleted: $deleted", deleted > 100)
        val oldestLeft = ids().minOf { ts.getValue(it) }
        assertTrue("nothing far past the limit is left", oldestLeft >= now - age - PruneWarnPolicy.BATCH_MS - PruneWarnPolicy.GRACE_MS)
    }

    // ---- DataStore store ----

    @Test fun dataStoreStore_roundTrip_bannerTiming() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("m.preferences_pb").also { it.delete() } })
        val s = DataStorePruneWarnStore(ds)
        assertEquals(PruneWarnState(), s.read())
        assertNull(s.banner().first())

        val full = PruneWarnState(age = AgeWarning(t0, t0 - 5, prevBoundary = t0 - 9), cap = CapWarning(t0, 42L))
        s.write(full)
        assertEquals(full, s.read())
        assertNull("notification path: no banner", s.banner().first())
        s.write(PruneWarnState())
        assertEquals(PruneWarnState(), s.read())

        s.write(PruneWarnState(pending = PendingBanner(age = true, cap = true, maxId = 7)))
        assertNull("not shown yet", s.read().age)
        assertNotNull(s.banner().first()!!.pending)
        s.markBannerShown(t0)
        val shown = s.read()
        assertEquals(AgeWarning(t0, PruneWarnPolicy.boundaryFor(t0)), shown.age)
        assertEquals(CapWarning(t0, 7L), shown.cap)
        assertNull(shown.pending)
        assertEquals(PruneNotice(t0 + 14L * day, PruneWarnPolicy.boundaryFor(t0)), s.banner().first()!!.notice)
        s.markBannerShown(t0 + day)
        assertEquals("first show wins", t0, s.read().age!!.at)
        s.dismissBanner()
        assertNull(s.banner().first())
        assertEquals("questa_device_maintenance", DataStorePruneWarnStore.FILE_NAME)
    }
}
