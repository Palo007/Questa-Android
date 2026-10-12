package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CustomViewEntity
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3 / T3.2 -- Room-backed "merged views persist and re-emit" acceptance test (see
 * `AnViewsMetricsTest`, `com.example.data.sync`, for the plain-JVM `SerializationEngine`-level
 * import/export/merge tests this file complements). Exercises the REAL `SyncManager` entry points
 * (`importSnapshotFromFile`/`exportSnapshotToFile`) against an in-memory Room database, which
 * requires Robolectric for the `Context`/`Room` dependency -- matches `Stage6FeatureTest`'s setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnViewsMetricsPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce() // WP-8.10: no background boot write overlaps this test's DataStore writes
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val syncDataStore = SyncDataStore(context)
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
        AppBootQuiesce.quiesce()
        db.close()
    }

    private fun backupWithViews(viewIds: List<String>): String {
        val viewsArr = viewIds.joinToString(",") { id ->
            """{ "id": "$id", "name": "View $id", "source": "overview", "group": "day", "chart": "list", "tags": [], "types": [], "metricId": null, "createdAt": 1000, "updatedAt": 2000 }"""
        }
        return """
            {
              "_backup": { "schema": 2 },
              "K": [], "SRC": [], "TID": [], "TT": [],
              "FM": {},
              "S": {
                "version": 1,
                "char": { "name": "Hero", "face": "🧙", "cls": "Warrior", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
                "tasks": [], "rewards": [], "tags": [], "devices": [],
                "lastCron": 0, "history": [], "charHistory": [], "monthlyBackups": [], "deletions": [],
                "prefs": {
                  "haptics": true,
                  "an": {
                    "views": [ $viewsArr ],
                    "metrics": [ { "id": "m-legacy", "name": "Kliky", "keyword": "kliky" } ]
                  }
                }
              },
              "E": []
            }
        """.trimIndent()
    }

    @Test
    fun importSnapshotFromFile_persistsViewsAndMetrics_toRoom() = runBlocking {
        val result = syncManager.importSnapshotFromFile(backupWithViews(listOf("v1", "v2")))
        assertTrue("import must succeed: ${(result.exceptionOrNull())}", result.isSuccess)

        val stored = repository.getAllCustomViewsSync()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.source == "overview" })
        assertEquals(setOf("v1", "v2"), stored.map { it.id }.toSet())

        val metricsJson = repository.getMetricsExtrasJson()
        val metricsArr = SerializationEngine.parseJsonArray(metricsJson)
        assertEquals(1, metricsArr.size)
    }

    @Test
    fun exportSnapshotToFile_reEmitsPersistedViewsAndLegacyMetric() = runBlocking {
        syncManager.importSnapshotFromFile(backupWithViews(listOf("v1", "v2")))

        val exportResult = syncManager.exportSnapshotToFile()
        assertTrue("export must succeed: ${exportResult.exceptionOrNull()}", exportResult.isSuccess)

        val reImported = SerializationEngine.importBackup(exportResult.getOrThrow())
        assertEquals(2, reImported.views.size)
        assertEquals(setOf("v1", "v2"), reImported.views.map { it.id }.toSet())

        val legacyMetric = reImported.metrics.single()
        assertEquals(
            "the legacy metric's missing-key-set must survive a full Room persistence round trip",
            setOf("id", "name", "keyword"),
            legacyMetric.keys
        )
    }

    @Test
    fun reImport_withViewRemoved_deletesStaleViewFromRoom_andExportReflectsIt() = runBlocking {
        syncManager.importSnapshotFromFile(backupWithViews(listOf("v1", "v2")))
        assertEquals(2, repository.getAllCustomViewsSync().size)

        // Re-import a backup where "v2" is gone -- simulates a merged/imported snapshot that no
        // longer contains it (deleted on another device). applyMergedSnapshotToDatabase must
        // delete the stale PWA-synced row, not just leave it stranded.
        syncManager.importSnapshotFromFile(backupWithViews(listOf("v1")))

        val stored = repository.getAllCustomViewsSync()
        assertEquals(1, stored.size)
        assertEquals("v1", stored.single().id)

        // And the next export must reflect that deletion too ("...persist and re-emit").
        val exportResult = syncManager.exportSnapshotToFile()
        val reImported = SerializationEngine.importBackup(exportResult.getOrThrow())
        assertEquals(1, reImported.views.size)
        assertEquals("v1", reImported.views.single().id)
    }

    @Test
    fun nativeQuickView_neverDeletedByAnViewsSync_andNeverAppearsOnTheWire() = runBlocking {
        // A pre-existing Kotlin-native "quick view" row (source == null) created independently of
        // any PWA sync -- see CustomViewEntity's class doc for why this table holds both kinds of
        // row. It must survive an unrelated an.views import/merge untouched, and must never be
        // put on the an.views wire itself.
        repository.insertCustomView(CustomViewEntity(id = "native-1", title = "Kliky 30D", chartType = "klik|30", createdAt = com.example.data.clock.PhysicalClock.nowMs()))

        syncManager.importSnapshotFromFile(backupWithViews(listOf("v1")))

        val stored = repository.getAllCustomViewsSync()
        assertEquals(2, stored.size) // native-1 + v1
        assertTrue("the native quick-view row must survive an unrelated an.views sync", stored.any { it.id == "native-1" && it.source == null })

        val exportResult = syncManager.exportSnapshotToFile()
        val reImported = SerializationEngine.importBackup(exportResult.getOrThrow())
        assertEquals(
            "the native-only row must never be built into the an.views wire array",
            setOf("v1"),
            reImported.views.map { it.id }.toSet()
        )
    }
}
