package com.example.data.sync

import com.example.data.inbox.InboxConsumer
import com.example.data.inbox.InboxFinisher
import com.example.data.inbox.InboxMetaWriter
import com.example.data.sync.backup.AutoBackupStep
import com.example.data.sync.events.EventsStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-8.I (W4 close): the production round-step list (8.7 / 8.8 / 8.9) and the two SyncManager
 * seams it uses (`backupSource` for the manual export, `clearPendingForcePush` on disconnect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSyncWiringTest : Wp89TestBase() {

    @Test fun steps_replaceBothBuiltIns_withThePublicW4Steps_inSlotOrder() {
        val d = device89()
        val steps = AppSyncWiring(context, d.db, d.repository, d.syncDataStore).steps
        assertEquals(5, steps.size)
        assertTrue(steps[0] is EventsStep)
        assertTrue(steps[1] is AutoBackupStep)
        assertTrue(steps[2] is InboxConsumer)
        assertTrue(steps[3] is InboxFinisher)
        assertTrue(steps[4] is InboxMetaWriter)
        assertEquals(listOf(StepSlot.EVENTS, StepSlot.BACKUPS, StepSlot.AFTER_RATCHET, StepSlot.POST_UPLOAD, StepSlot.BACKUPS),
            steps.map { it.slot })
        // No SyncManager built-in (private inner EventsStep / BackupsStep) is in the list.
        assertFalse(steps.any { it.javaClass.name.startsWith(SyncManager::class.java.name + "$") })
        // Exactly one BACKUPS step at order 0: a second auto backup would upload every due tier twice.
        assertEquals(1, steps.count { it.slot == StepSlot.BACKUPS && it.order == 0 })
    }

    @Test fun appAndWorker_buildTheSameStepList() {
        fun src(path: String) = listOf(File("src/main/java/com/example/$path"), File("app/src/main/java/com/example/$path"))
            .first { it.exists() }.readText()
        for (path in listOf("QuestaApplication.kt", "data/sync/SyncWorker.kt")) {
            val text = src(path)
            assertTrue(path, text.contains("AppSyncWiring(applicationContext, database, repository, syncDataStore)"))
            assertTrue(path, text.contains("roundSteps = wiring.steps"))
            assertTrue(path, text.contains("backupSource = wiring.liveBackup"))
            assertEquals(path, 1, Regex("SyncManager\\(").findAll(text.substringAfter("override fun")).count())
        }
    }

    @Test fun manualExport_usesTheLiveBackupSource() = runBlocking {
        val d = device89()
        val now = 1_760_000_000_000L
        val manager = SyncManager(context, d.repository, d.syncDataStore, null, d.tokens,
            hlcClock = d.hlc, roundSteps = emptyList(), physicalClock = { now }, backupSource = d.live)
        val text = manager.exportSnapshotToFile().getOrThrow()
        assertEquals(d.live.backupText(d.live.exporter { now }), text)
    }

    @Test fun disconnect_clearsThePendingForcePush() = runBlocking {
        val d = device89()
        val manager = SyncManager(context, d.repository, d.syncDataStore, null, d.tokens,
            hlcClock = d.hlc, roundSteps = emptyList(),
            clearPendingForcePush = { d.backupPrefs.setPendingForcePush(false) })
        d.backupPrefs.setPendingForcePush(true)
        manager.disconnect()
        assertFalse(d.backupPrefs.pendingForcePush())
    }
}
