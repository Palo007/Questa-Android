package com.example.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.AutoBackupStep
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.TieredBackupEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The BACKUPS step rides a successful round and goes through DropboxService (interlock). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBackupStepTest : RoundTestBase() {
    private val tmp: File = File.createTempFile("bkstep", ".preferences_pb").also { it.delete() }
    private val prefs = BackupPrefs(PreferenceDataStoreFactory.create(produceFile = { tmp }))
    private var backupError: String? = null

    @After fun tearDown() { tmp.delete() }

    @org.junit.Before fun awaitAppStartup() = runBlocking {
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    private fun engine() = TieredBackupEngine(
        prefs = prefs,
        buildBackupText = { "{\"b\":1}" },
        deviceId = { "dev-00abcdef" },
        saveLastExportTs = {},
        lastBackupError = { backupError },
        saveLastBackupError = { backupError = it },
        logExport = { _, _ -> },
    )

    private fun backupUploads() = fake.requests.filter { it.endpoint == "/2/files/upload" }
        .mapNotNull { it.arg?.get("path")?.jsonPrimitive?.content }.filter { it.startsWith("/questa-backups/") }

    @Test fun aSuccessfulRound_firesTheDueTier() = runBlocking {
        prefs.setAutoBackupEnabled(AutoBackupEnabled(fourHour = true))
        val d = RoundDevice(context, "dev-00abcdef", roundSteps = listOf(AutoBackupStep(engine())))
        try {
            d.link()
            d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
            assertEquals(SyncResult.Ok, d.manager.sync())
            val up = backupUploads()
            assertEquals(1, up.size)
            assertTrue(up.single().startsWith("/questa-backups/4hour-abcdef-00-"))
            assertEquals(null, backupError)
        } finally { d.close() }
    }

    @Test fun aFailedRound_firesNothing() = runBlocking {
        prefs.setAutoBackupEnabled(AutoBackupEnabled(fourHour = true))
        val d = RoundDevice(context, "dev-00abcdef", roundSteps = listOf(AutoBackupStep(engine())))
        try {
            d.link()
            fake.scriptNext("/2/files/download", 400, "{\"error_summary\":\"bad\"}")
            assertTrue(d.manager.sync() is SyncResult.Error)
            assertTrue(backupUploads().isEmpty())
        } finally { d.close() }
    }
}
