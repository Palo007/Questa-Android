package com.example

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * CR-KT-008 (KT-95): every WRITE of `task.tags` stores tag IDS, as the PWA does (Opti app.js:3256
 * `tagById`, 3272 `tagChips`, 3299-3303 `addTagToEdit`/`toggleEditTag`). Reads stay tolerant of
 * legacy name tokens. Fixture: `fixtures/CR-KT-008/task_with_tag_ids.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagIdWriteTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var viewModel: MainViewModel

    private val fx: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("fixtures/CR-KT-008/task_with_tag_ids.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }
    private val healthId = "mg99c0e8k3f7q"
    private val workId = "mg99ennkx2b9d"
    private val pwaId = "mg99gapsa1c4e"
    private val legacyId = "kt-legacy-1"

    private fun strings(a: JsonArray) = a.map { it.jsonPrimitive.content }
    private fun expected(key: String) = strings(fx.getValue(key).jsonArray)

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sameThreadExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(sameThreadExecutor)
            .setTransactionExecutor(sameThreadExecutor)
            .build()
        val syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore,
            database = db
        )
        viewModel = MainViewModel(repository, SyncManager(context, repository, syncDataStore), syncDataStore, processLifecycle = null)
        runBlocking {
            fx.getValue("tags").jsonArray.forEachIndexed { i, t ->
                db.tagDao().insertTag(SerializationEngine.parseTagEntity(t.jsonObject).copy(orderIndex = i.toLong()))
            }
            db.taskDao().insertTask(SerializationEngine.parseTaskEntity(fx.getValue("pwaTask").jsonObject))
            db.taskDao().insertTask(SerializationEngine.parseTaskEntity(fx.getValue("legacyNameTask").jsonObject))
        }
    }

    @After
    fun tearDown() {
        db.close()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    private fun task(id: String) = runBlocking { db.taskDao().getTaskByIdSync(id)!! }
    private fun exportedTags(id: String) = strings(SerializationEngine.buildTaskJson(task(id)).getValue("tags").jsonArray)

    // Fails before the fix: saveEditedTask writes the name token "work" unchanged.
    @Test fun `editor save converts known name tokens to ids and keeps unknown tokens`() = runBlocking {
        viewModel.saveEditedTask(task(legacyId).copy(title = "Write report v2"))
        awaitAssert {
            assertEquals(expected("expected_legacyNameTask_tags_after_editor_save"), task(legacyId).tags)
        }
    }

    // Fails before the fix: the export carries the name "work", which the PWA cannot resolve.
    @Test fun `exported task JSON after an editor save holds the id not the name`() = runBlocking {
        viewModel.saveEditedTask(task(legacyId).copy(title = "Write report v2"))
        awaitAssert {
            val tags = exportedTags(legacyId)
            assertTrue(workId in tags)
            assertFalse("work" in tags)
        }
    }

    // Fails before the fix: createTask stores whatever names the editor passed.
    @Test fun `a new task saved with a tag name stores the tag id`() = runBlocking {
        viewModel.createTask(type = "todo", title = "New", tags = listOf("health"))
        awaitAssert {
            val created = db.taskDao().getAllTasksSync().single { it.title == "New" }
            assertEquals(listOf(healthId), created.tags)
        }
    }

    // The id is chosen before the task is saved, the tag row gets that same id, and the export shows it.
    @Test fun `a tag created in the editor ends in the task as its id`() = runBlocking {
        val newId = com.example.ui.newTagId()
        viewModel.createTag("errands", "#ffbe5c", newId)
        viewModel.saveEditedTask(task(pwaId).copy(tags = task(pwaId).tags + newId))
        awaitAssert {
            assertEquals(newId, db.tagDao().getAllTagsSync().single { it.name == "errands" }.id)
            assertEquals(listOf(healthId, newId), exportedTags(pwaId))
        }
    }

    // Fails before the fix: bulk remove compared raw tokens, so the id missed the legacy name.
    @Test fun `bulk remove by id removes the legacy name token`() = runBlocking {
        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf(legacyId))
        viewModel.bulkEditTags(add = emptySet(), remove = setOf(workId))
        awaitAssert {
            assertEquals(expected("expected_legacyNameTask_tags_after_bulk_remove_work"), task(legacyId).tags)
        }
    }

    @Test fun `bulk remove by id removes the PWA id token`() = runBlocking {
        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf(pwaId))
        viewModel.bulkEditTags(add = emptySet(), remove = setOf(healthId))
        awaitAssert {
            assertEquals(expected("expected_pwaTask_tags_after_bulk_remove_health"), task(pwaId).tags)
        }
    }

    // Fails before the fix: the legacy task held "work", so the id was appended as a second copy.
    @Test fun `bulk add by id adds the id and never duplicates a tag held by name`() = runBlocking {
        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf(pwaId, legacyId))
        viewModel.bulkEditTags(add = setOf(workId), remove = emptySet())
        val exp = fx.getValue("expected_tags_after_bulk_add_work_to_both").jsonObject
        awaitAssert {
            assertEquals(strings(exp.getValue(pwaId).jsonArray), task(pwaId).tags)
            assertEquals(strings(exp.getValue(legacyId).jsonArray), task(legacyId).tags)
            assertEquals(strings(exp.getValue(pwaId).jsonArray), exportedTags(pwaId))
        }
    }

    // No mass rewrite: a task outside the bulk selection keeps its legacy tokens and its stamp.
    @Test fun `bulk edit and tag creation do not restamp untouched tasks`() = runBlocking {
        val before = task(legacyId)
        viewModel.createTag("errands", "#ffbe5c", "kt-new-tag")
        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf(pwaId))
        viewModel.bulkEditTags(add = setOf(workId), remove = emptySet())
        awaitAssert {
            assertEquals(listOf(healthId, workId), task(pwaId).tags)
            assertEquals(before, task(legacyId))
        }
    }

    // A PWA task that already holds ids is exported unchanged.
    @Test fun `a PWA task with tag ids round-trips through the export unchanged`() {
        assertEquals(strings(fx.getValue("pwaTask").jsonObject.getValue("tags").jsonArray), exportedTags(pwaId))
    }

    // Deleting a tag still strips both spellings (MainViewModel.deleteTag).
    @Test fun `deleting a tag removes its id and its legacy name from tasks`() = runBlocking {
        viewModel.deleteTag(workId, "work")
        viewModel.deleteTag(healthId, "health")
        awaitAssert {
            assertEquals(listOf("orphan-name"), task(legacyId).tags)
            assertEquals(emptyList<String>(), task(pwaId).tags)
            assertNull(db.tagDao().getAllTagsSync().firstOrNull { it.id == workId })
        }
    }

    private suspend fun <T> awaitAssert(timeoutMs: Long = 2000, pollMs: Long = 10, assertion: suspend () -> T): T {
        val start = System.currentTimeMillis()
        var lastError: Throwable? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                return assertion()
            } catch (e: Throwable) {
                lastError = e
                kotlinx.coroutines.delay(pollMs)
            }
        }
        throw lastError ?: AssertionError("Timeout waiting for assertion")
    }
}
