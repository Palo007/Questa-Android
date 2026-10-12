package com.example.data.sync.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** An in-memory `/questa-backups/` folder. */
class FakeBackupRemote : BackupRemote {
    val files = linkedMapOf<String, String>() // name -> text
    val uploads = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var listThrows = false
    var uploadThrows: String? = null

    override suspend fun list(folder: String): List<String> {
        if (listThrows) throw BackupRemoteException("list failed")
        return files.keys.toList()
    }

    override suspend fun upload(path: String, text: String) {
        uploadThrows?.let { throw BackupRemoteException(it) }
        uploads += path
        files[path.removePrefix(BackupTierEngine.FOLDER)] = text
    }

    override suspend fun delete(path: String) {
        deletes += path
        files.remove(path.removePrefix(BackupTierEngine.FOLDER))
    }
}

/** auto-backup-rotation.test.js R1-R5 (pure rotation). */
class BackupRotationTest {
    private val t0 = "20260701-"

    @Test fun r1_listTier_isPrefixAndDeviceScoped() {
        val listing = listOf("4hour-123456-00-${t0}120000.json", "daily-123456-00-${t0}120000.json",
            "4hour-123456-01-${t0}130000.json", "4hour-abcdef-00-${t0}120000.json", "daily-123456-01-${t0}130000.json")
        assertEquals(listOf("4hour-123456-00-${t0}120000.json", "4hour-123456-01-${t0}130000.json"),
            BackupTierEngine.tierFiles(listing, BkTier.FOUR_HOUR, "123456"))
        assertEquals(2, BackupTierEngine.tierFiles(listing, BkTier.DAILY, "123456").size)
        assertEquals(1, BackupTierEngine.tierFiles(listing, BkTier.FOUR_HOUR, "abcdef").size)
    }

    @Test fun r2_r5_sameSlot_keepsTheNewest_deletesTheOlder() {
        val listing = listOf("4hour-123456-00-${t0}120000.json", "4hour-123456-00-${t0}140000.json", "daily-123456-00-${t0}120000.json")
        assertEquals(listOf("/questa-backups/4hour-123456-00-${t0}120000.json"),
            BackupTierEngine.rotation(listing, BkTier.FOUR_HOUR, "123456", 0))
    }

    @Test fun r3_otherDevices_areNeverTouched() {
        val listing = listOf("4hour-123456-00-${t0}120000.json", "4hour-123456-00-${t0}130000.json",
            "4hour-abcdef-00-${t0}120000.json", "4hour-abcdef-00-${t0}130000.json")
        val del = BackupTierEngine.rotation(listing, BkTier.FOUR_HOUR, "123456", 0)
        assertEquals(1, del.size)
        assertTrue(del.none { it.contains("abcdef") })
    }

    @Test fun r5b_threeInOneSlot_deletesSecondNewestThenOldest() {
        val listing = listOf("4hour-123456-00-${t0}100000.json", "4hour-123456-00-${t0}120000.json", "4hour-123456-00-${t0}140000.json")
        assertEquals(listOf("/questa-backups/4hour-123456-00-${t0}120000.json", "/questa-backups/4hour-123456-00-${t0}100000.json"),
            BackupTierEngine.rotation(listing, BkTier.FOUR_HOUR, "123456", 0))
    }

    @Test fun theJustWrittenFile_survivesEvenWhenAnOlderOneSortsHigher() {
        val listing = listOf("weekly-123456-00-${t0}170000.json", "weekly-123456-00-${t0}120000.json")
        val del = BackupTierEngine.rotation(listing, BkTier.WEEKLY, "123456", 0, "/questa-backups/weekly-123456-00-${t0}120000.json")
        assertEquals(listOf("/questa-backups/weekly-123456-00-${t0}170000.json"), del)
    }

    @Test fun overCount_evictsTheOldestSlots_neverTheCurrentOne() {
        val listing = (0..5).map { "weekly-123456-0$it-${t0}1${it}0000.json" }
        val del = BackupTierEngine.rotation(listing, BkTier.WEEKLY, "123456", 0)
        // slot 0 is current and oldest; 1 and 2 are the next oldest.
        assertEquals(listOf("/questa-backups/weekly-123456-01-${t0}110000.json", "/questa-backups/weekly-123456-02-${t0}120000.json"), del)
    }

    @Test fun healedIndex_isMaxSlotPlusOne_wrapping() {
        assertEquals(4, BackupTierEngine.healedIndex((0..3).map { "4hour-123456-0$it-${t0}120000.json" }, BkTier.FOUR_HOUR, "123456"))
        assertEquals(0, BackupTierEngine.healedIndex(emptyList(), BkTier.FOUR_HOUR, "123456"))
        assertEquals(0, BackupTierEngine.healedIndex((0..9).map { "4hour-123456-%02d-${t0}120000.json".format(it) }, BkTier.FOUR_HOUR, "123456"))
    }
}

/** auto-backup-cycling / -rotation / -tiers engine vectors (bookkeeping, R4, C2-C11, lastBackupError). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class TieredBackupEngineTest {
    private val tmp: File = File.createTempFile("bk89", ".preferences_pb").also { it.delete() }
    private val store = PreferenceDataStoreFactory.create(produceFile = { tmp })
    private val prefs = BackupPrefs(store)
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1782907200000L // 2026-07-01T12:00:00Z
    private var now = t0
    private var builds = 0
    private var lastExport = 0L
    private var backupError: String? = null
    private val logs = mutableListOf<Pair<String, String>>()
    private val remote = FakeBackupRemote()

    private val engine = TieredBackupEngine(
        prefs = prefs,
        buildBackupText = { builds++; "{\"backup\":true}" },
        deviceId = { "dev-00123456" },
        saveLastExportTs = { lastExport = it },
        lastBackupError = { backupError },
        saveLastBackupError = { backupError = it },
        logExport = { a, b -> logs += a to b },
        clock = { now },
        tz = { utc },
    )

    @After fun tearDown() { tmp.delete() }

    @org.junit.Before fun awaitAppStartup() = runBlocking {
        (androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>() as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    private fun name(t: BkTier, slot: Int, stamp: String = "20260701-110000") = "${t.prefix}-123456-%02d-$stamp.json".format(slot)

    @Test fun c1_bookkeepingDefaults_andNormalisation() = runBlocking {
        assertTrue(prefs.tierStates().values.all { it == TierState() })
        store.edit { it[BackupPrefs.TIER_STATE] = "{\"weekly\":{\"idx\":2,\"lastTs\":5}}" }
        assertEquals(TierState(0, 5, 2), prefs.tierStates()[BkTier.WEEKLY])
        store.edit { it[BackupPrefs.TIER_STATE] = "not json" }
        assertTrue(prefs.tierStates().values.all { it == TierState() })
    }

    @Test fun c2_selfHeal_continuesAfterTheHighestRemoteSlot() = runBlocking {
        (0..3).forEach { remote.files[name(BkTier.FOUR_HOUR, it)] = "x" }
        engine.fire(BkTier.FOUR_HOUR, "text", remote)
        assertEquals(listOf("/questa-backups/4hour-123456-04-20260701-120000.json"), remote.uploads)
        assertEquals(TierState(1, t0, 5), prefs.tierStates()[BkTier.FOUR_HOUR])
        assertTrue(remote.deletes.isEmpty())
    }

    @Test fun c3_freshDevice_slotZero() = runBlocking {
        engine.fire(BkTier.FOUR_HOUR, "text", remote)
        assertTrue(remote.uploads.single().contains("-00-"))
        assertEquals(1, prefs.tierStates().getValue(BkTier.FOUR_HOUR).idx)
    }

    @Test fun c4_fullTier_healsBackToSlotZero() = runBlocking {
        (0..9).forEach { remote.files[name(BkTier.FOUR_HOUR, it)] = "x" }
        engine.fire(BkTier.FOUR_HOUR, "text", remote)
        assertTrue(remote.uploads.single().contains("4hour-123456-00-"))
        assertEquals(1, prefs.tierStates().getValue(BkTier.FOUR_HOUR).idx)
    }

    @Test fun c5_knownBookkeeping_isUsedVerbatim() = runBlocking {
        prefs.saveTierStates(mapOf(BkTier.FOUR_HOUR to TierState(1, 1L, 2)))
        (0..5).forEach { remote.files[name(BkTier.FOUR_HOUR, it)] = "x" }
        engine.fire(BkTier.FOUR_HOUR, "text", remote)
        assertTrue(remote.uploads.single().contains("4hour-123456-02-"))
        assertEquals(3, prefs.tierStates().getValue(BkTier.FOUR_HOUR).idx)
    }

    @Test fun c6_aListFailure_stillUploadsAtSlotZero() = runBlocking {
        remote.listThrows = true
        engine.fire(BkTier.FOUR_HOUR, "text", remote)
        assertTrue(remote.uploads.single().contains("-00-"))
        assertEquals(1, prefs.tierStates().getValue(BkTier.FOUR_HOUR).bkVersion)
    }

    @Test fun c7_c8_weeklyIndex_advancesAndWraps_zeroPadded() = runBlocking {
        prefs.saveTierStates(mapOf(BkTier.WEEKLY to TierState(1, 1L, 3)))
        engine.fire(BkTier.WEEKLY, "text", remote)
        assertTrue(remote.uploads.single().contains("weekly-123456-03-"))
        assertEquals(TierState(1, t0, 0), prefs.tierStates()[BkTier.WEEKLY])
    }

    @Test fun c10_overCap_evictsTheOldest_notTheJustWritten() = runBlocking {
        prefs.saveTierStates(mapOf(BkTier.WEEKLY to TierState(1, 1L, 0)))
        (0..5).forEach { remote.files[name(BkTier.WEEKLY, it, "20260630-0${it}0000")] = "x" }
        engine.fire(BkTier.WEEKLY, "text", remote)
        assertEquals(4, BackupTierEngine.tierFiles(remote.files.keys.toList(), BkTier.WEEKLY, "123456").map { it.split('-')[2] }.distinct().size)
        assertTrue(remote.files.containsKey("weekly-123456-00-20260701-120000.json"))
        assertTrue(remote.files.containsKey("weekly-123456-05-20260630-050000.json"))
        assertFalse(remote.files.keys.any { it.startsWith("weekly-123456-01-") || it.startsWith("weekly-123456-02-") })
    }

    @Test fun c11_aFutureStampedStaleFile_isDeleted_theFreshOneSurvives() = runBlocking {
        prefs.saveTierStates(mapOf(BkTier.WEEKLY to TierState(1, 1L, 0)))
        remote.files["weekly-123456-00-20260701-170000.json"] = "stale"
        engine.fire(BkTier.WEEKLY, "text", remote)
        assertEquals(listOf("/questa-backups/weekly-123456-00-20260701-170000.json"), remote.deletes)
        assertTrue(remote.files.containsKey("weekly-123456-00-20260701-120000.json"))
    }

    @Test fun r4_oneBuild_isSharedByEveryDueTier() = runBlocking {
        prefs.setAutoBackupEnabled(AutoBackupEnabled(fourHour = true, daily = true))
        engine.maybeAutoExport(remote)
        assertEquals(1, builds)
        assertEquals(2, remote.uploads.size)
        assertTrue(remote.uploads[0].contains("/4hour-"))
        assertTrue(remote.uploads[1].contains("/daily-"))
    }

    @Test fun r4b_noTierOn_buildsNothing() = runBlocking {
        prefs.setAutoBackupEnabled(AutoBackupEnabled())
        engine.maybeAutoExport(remote)
        assertEquals(0, builds)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun aTierIsNotDueBeforeItsBoundary() = runBlocking {
        prefs.setAutoBackupEnabled(AutoBackupEnabled(fourHour = true))
        engine.maybeAutoExport(remote)
        now = t0 + 3_600_000L
        engine.maybeAutoExport(remote)
        assertEquals(1, remote.uploads.size)
        now = t0 + 4 * 3_600_000L
        engine.maybeAutoExport(remote)
        assertEquals(2, remote.uploads.size)
    }

    @Test fun success_stampsLastExport_logs_andClearsTheBackupError() = runBlocking {
        backupError = "daily backup failed: x"
        engine.fire(BkTier.DAILY, "text", remote)
        assertEquals(t0, lastExport)
        assertNull(backupError)
        assertEquals("Auto Backup (daily)", logs.single().first)
        assertEquals("Uploaded /questa-backups/daily-123456-00-20260701-120000.json to Dropbox", logs.single().second)
    }

    @Test fun failure_writesItsOwnBackupErrorKey_andKeepsTheBookkeeping() = runBlocking {
        remote.uploadThrows = "Dropbox backup upload failed: 507"
        engine.fire(BkTier.MONTHLY, "text", remote)
        assertEquals("monthly backup failed: Dropbox backup upload failed: 507", backupError)
        assertEquals(0L, lastExport)
        assertEquals("the slot index does not advance", 0, prefs.tierStates().getValue(BkTier.MONTHLY).idx)
        assertTrue(logs.isEmpty())
    }

    @Test fun manualSaveToDropbox_writesTheLegacyPath() = runBlocking {
        engine.uploadManual("text", remote)
        assertEquals(listOf(TieredBackupEngine.EXPORT_BACKUP_PATH), remote.uploads)
        assertEquals(t0, lastExport)
    }
}

/** auto-backup-tiers.test.js T1-T2 and §4.3 row :58 (EXPORT_INTERVAL_DAYS migrated, key deleted). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ExportIntervalMigrationTest {
    private val tmp: File = File.createTempFile("bkmig", ".preferences_pb").also { it.delete() }
    private val store = PreferenceDataStoreFactory.create(produceFile = { tmp })
    private val prefs = BackupPrefs(store)

    @After fun tearDown() { tmp.delete() }

    @org.junit.Before fun awaitAppStartup() = runBlocking {
        (androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>() as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    @Test fun t2_theMigrateTable() {
        assertEquals(AutoBackupEnabled(daily = true), AutoBackupEnabled.fromExportIntervalDays(1))
        assertEquals(AutoBackupEnabled(daily = true), AutoBackupEnabled.fromExportIntervalDays(3))
        assertEquals(AutoBackupEnabled(weekly = true), AutoBackupEnabled.fromExportIntervalDays(7))
        assertEquals(AutoBackupEnabled(monthly = true), AutoBackupEnabled.fromExportIntervalDays(14))
        assertEquals(AutoBackupEnabled(monthly = true), AutoBackupEnabled.fromExportIntervalDays(30))
        assertEquals(AutoBackupEnabled(), AutoBackupEnabled.fromExportIntervalDays(0))
        assertEquals(AutoBackupEnabled(), AutoBackupEnabled.fromExportIntervalDays(null))
    }

    @Test fun t1_freshInstall_allOff_andTheKeyIsWritten() = runBlocking {
        assertEquals(AutoBackupEnabled(), prefs.autoBackupEnabled())
        assertEquals("{\"fourHour\":false,\"daily\":false,\"weekly\":false,\"monthly\":false}", store.data.first()[SyncKeys.AUTO_BACKUP_TIERS])
    }

    @Test fun legacyInterval_isMigratedOnce_andTheOldKeyDeleted() = runBlocking {
        store.edit { it[SyncKeys.EXPORT_INTERVAL_DAYS] = 7 }
        assertEquals(AutoBackupEnabled(weekly = true), prefs.autoBackupEnabled())
        assertNull(store.data.first()[SyncKeys.EXPORT_INTERVAL_DAYS])
        assertFalse(prefs.migratedToastOwed())
        prefs.setAutoBackupEnabled(AutoBackupEnabled(daily = true))
        assertEquals("the stored selection wins from now on", AutoBackupEnabled(daily = true), prefs.autoBackupEnabled())
    }

    @Test fun fourteenDays_becomesMonthly_andOwesTheToast() = runBlocking {
        store.edit { it[SyncKeys.EXPORT_INTERVAL_DAYS] = 14 }
        assertEquals(AutoBackupEnabled(monthly = true), prefs.autoBackupEnabled())
        assertTrue(prefs.migratedToastOwed())
    }

    @Test fun t3_setOneTier_keepsTheOthers() = runBlocking {
        prefs.setAutoBackupEnabled(prefs.autoBackupEnabled().with(BkTier.DAILY, true))
        prefs.setAutoBackupEnabled(prefs.autoBackupEnabled().with(BkTier.WEEKLY, true).with(BkTier.FOUR_HOUR, true))
        prefs.setAutoBackupEnabled(prefs.autoBackupEnabled().with(BkTier.DAILY, false))
        assertEquals(AutoBackupEnabled(fourHour = true, weekly = true), prefs.autoBackupEnabled())
    }
}
