package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3 / T3.3 (D5) + T3.4 (D8) -- Robolectric, real-Room-backed tests exercising the ACTUAL
 * production wiring the plain-JVM [com.example.data.sync.CharacterMergeAndLastCronTest] assumes
 * is correct upstream: that [SyncManager.buildLocalSnapshot] emits the character's real STORED
 * `updatedAt` (not a fresh timestamp minted at snapshot-build time), and that
 * [SyncDataStore.localSavedAt] gets stamped at the moment of a genuine local
 * [RpgRepository] save -- not only after a full Dropbox sync round completes. Same setup pattern
 * as `AnViewsMetricsPersistenceTest`/`Stage6FeatureTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterUpdatedAtSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncManager: SyncManager
    private lateinit var syncDataStore: SyncDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore
        )
        syncManager = SyncManager(context, repository, syncDataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun buildLocalSnapshot_emitsStoredCharacterUpdatedAt_notAFreshTimestamp() = runBlocking {
        // An old, deliberately-stale updatedAt -- if buildLocalSnapshot were still stamping
        // System.currentTimeMillis() at build time (the pre-D5-fix bug), the emitted value would
        // be close to "now" instead of this old value.
        val staleUpdatedAt = 12345L
        repository.insertCharacter(CharacterEntity(id = 1, lvl = 5, xp = 200, gold = 50.0, updatedAt = staleUpdatedAt))

        val snap = syncManager.buildLocalSnapshot()
        val emittedUpdatedAt = snap["char"]!!.jsonObject["updatedAt"]!!.jsonPrimitive.long

        assertEquals(staleUpdatedAt, emittedUpdatedAt)
        assertTrue(
            "emitted updatedAt must not have been re-stamped to current time",
            System.currentTimeMillis() - emittedUpdatedAt > 60_000L
        )
    }

    @Test
    fun rpgEngineMutation_stampsCharacterUpdatedAt_onGenuineChange() = runBlocking {
        val oldStamp = 1000L
        // hp below maxHp so buyPotion's early-return guard doesn't short-circuit the mutation.
        repository.insertCharacter(CharacterEntity(id = 1, lvl = 1, xp = 0, hp = 10.0, maxHp = 50.0, gold = 100.0, updatedAt = oldStamp))

        // buyPotion is a genuine character mutation (gold/hp change).
        repository.buyPotion("potion")

        val updated = repository.getCharacterSync()
        assertTrue("a genuine mutation must bump updatedAt past its old stamp", updated.updatedAt > oldStamp)
    }

    @Test
    fun localSavedAt_isStampedOnRealLocalSave_notOnlyAfterASyncRound() = runBlocking {
        // No sync ever runs in this test -- if this Robolectric Context's DataStore file happens
        // to carry state from an earlier test method, `before` may not be 0, so assert on RELATIVE
        // change (a real, recent stamp appears) rather than an assumed zero baseline.
        val justBeforeSave = System.currentTimeMillis()
        repository.insertCharacter(CharacterEntity(id = 1, lvl = 2, updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val after = syncDataStore.localSavedAt.first()

        assertTrue("a genuine local Room save must stamp localSavedAt immediately, without any sync ever running", after >= justBeforeSave)
    }

    @Test
    fun localSavedAt_isNotStampedByEventLogInsertsAlone() = runBlocking {
        // Matches PWA: the event log lives outside S/save()'s __savedAt stamp (app.js:233-272 vs.
        // the separate logEvent()/IDB path) -- an event-only write carries no information about
        // the synced state and must not count as a "local save" for the stale-local guard.
        val before = syncDataStore.localSavedAt.first()
        repository.insertLog(EventLogEntity(kind = "appOpened", timestamp = com.example.data.clock.PhysicalClock.nowMs()))
        val after = syncDataStore.localSavedAt.first()
        assertEquals(before, after)
    }
}
