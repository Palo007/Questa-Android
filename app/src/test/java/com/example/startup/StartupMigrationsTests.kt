package com.example.startup

import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.DropboxConfigRule
import com.example.data.sync.auth.FakeTokenEndpoint
import com.example.data.sync.auth.InterlockPolicy
import com.example.data.sync.auth.TokenSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import com.example.seedFirstLaunch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** WP-8.21 StartupOrderTest: 1 DB, 2 marker, 2b tokens, (2c restore), 3 base, 4 app key, 5 markDone. */
class StartupOrderTest : StartupTestBase() {
    @Test fun freshInstall_stepsRunInOrder() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals(listOf("1", "2", "2b", "3", "4", "5"), h.order)
    }

    @Test fun legacyInstall_stepsRunInOrder_markerDecidedBeforeTokenMigration() = runBlocking {
        seedLinkedLegacy()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(listOf("1", "2", "2b", "3", "4", "5"), h.order)
        assertEquals("2:marker=${DropboxConfig.LEGACY_SENTINEL}", h.traces[1])
    }

    @Test fun restoredCopy_restoreResetRunsAfterTokenMigration_beforeBase() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfigRule.TEST_APP_KEY }
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(listOf("1", "2", "2b", "2c", "3", "4", "5"), h.order)
    }
}

/** WP-8.21 FreshInstallTest (+ the W1 step 0 file checks). */
class FreshInstallTest : StartupTestBase() {
    @Test fun step0_fileChecksOnly() {
        val dir = Files.createTempDirectory("wp821-step0").toFile()
        try {
            val dbFile = File(dir, "questa_database")
            val dsFile = File(dir, "questa_sync_preferences.preferences_pb")
            assertEquals(StartupProbe(false, false), StartupMigrations.captureStep0(dbFile, dsFile))
            dsFile.writeBytes(ByteArray(0))
            assertEquals("an empty DataStore file has no key", StartupProbe(false, false), StartupMigrations.captureStep0(dbFile, dsFile))
            dsFile.writeBytes(byteArrayOf(10, 3))
            dbFile.writeBytes(byteArrayOf(1))
            assertEquals(StartupProbe(true, true), StartupMigrations.captureStep0(dbFile, dsFile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun step0_usesTheRealDatabaseAndDataStorePaths() {
        // Robolectric gives each test its own data dir, but the process-wide `syncDataStore`
        // delegate keeps the first one, so write the files where production has them.
        val dbFile = context.getDatabasePath("questa_database")
        val dsFile = File(context.filesDir, "datastore/questa_sync_preferences.preferences_pb")
        dbFile.parentFile?.mkdirs()
        dsFile.parentFile?.mkdirs()
        try {
            assertEquals(StartupProbe(false, false), StartupMigrations.captureStep0(context))
            dbFile.writeBytes(byteArrayOf(1))
            dsFile.writeBytes(byteArrayOf(10, 3))
            assertEquals(StartupProbe(true, true), StartupMigrations.captureStep0(context))
        } finally {
            dbFile.delete()
            dsFile.delete()
        }
    }

    @Test fun freshInstall_markerIsAppKey_noExport_noClearing() = runBlocking {
        h.ds.setHapticsEnabled(false)
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        assertTrue(h.exports().isEmpty())
        assertEquals(1, h.barrierOpened)
        assertEquals(StartupMigrations.STATUS_DONE, h.lastStatus)
        assertEquals(InstallStamp.FRESH, h.stamp.read())
        assertFalse(h.ds.hapticsEnabled.first())
        // relaunch: nothing re-runs
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertTrue(h.exports().isEmpty())
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
    }
}

/** WP-8.21 FreshInstallSeedRaceTest (r6 m-R5-3). */
class FreshInstallSeedRaceTest : StartupTestBase() {
    @Test fun seedRunsAtOnce_classifiedFresh_writesOnlyAfterBarrier() = runBlocking {
        val dir = Files.createTempDirectory("wp821-seed").toFile()
        try {
            // W1 step 0 on an empty install, before anything else runs.
            val probe = StartupMigrations.captureStep0(File(dir, "db"), File(dir, "ds"))
            val barrier = CompletableDeferred<Unit>()
            val seed = launch { seedFirstLaunch(h.repo, FirstLaunchSeedGate.forDir(h.noBackupDir)) { barrier.await() } }
            repeat(5) { yield() }
            assertEquals("the seed must not write before the barrier", 0, taskCount())
            assertNull(h.db.characterDao().getCharacterSync())

            val runner = StartupMigrationRunner(
                openDatabase = { h.db.openHelper.writableDatabase },
                prefs = h.prefs,
                tokenMigration = {},
                baseStoreMigration = {},
                appKeyMigration = { h.appKeyMigration().runIfNeeded() },
                tokens = h.tokens,
                installStamp = h.stamp,
                markDone = { barrier.complete(Unit) }
            )
            assertTrue(runner.run(probe.dbExisted, probe.dsHadKeys))
            seed.join()

            assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
            assertTrue("fresh: no export", h.exports().isEmpty())
            assertEquals(4, taskCount())
            assertEquals(2, h.repo.allRewards.first().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** WP-8.21 LegacyUpgradeTest. */
class LegacyUpgradeTest : StartupTestBase() {
    @Test fun legacyKeysNoMarker_migratesOnce() = runBlocking {
        seedLinkedLegacy()
        val before = taskCount()

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        val export = h.exports().single()
        val env = Json.parseToJsonElement(export.readText()) as JsonObject
        assertNotNull("a schema-2 backup envelope", env["_backup"])
        assertTrue(export.readText().contains("T0"))
        // the base moved, bound to the old key, so it is stale for APP_KEY (D8)
        val base = h.baseStore.read()!!
        assertEquals(DropboxConfig.LEGACY_SENTINEL, base.appKey)
        assertEquals("rev-legacy", base.rev)
        assertNull(h.baseStore.validFor(DropboxConfigRule.TEST_APP_KEY, null))
        // watermarks and fileCounts cleared
        val p = h.p()
        assertNull(p[SyncKeys.EVT_FILE_COUNTS])
        assertNull(p[SyncDataStore.EVT_FILE_REVS])
        assertNull(p[SyncDataStore.EVT_LAST_UPLOAD_TS])
        assertNull(p[SyncDataStore.EVT_LAST_PULL_AT])
        assertNull(p[SyncKeys.EVT_BAD_REVS])
        assertEquals(before, taskCount())
        // the legacy grant is out of the backed-up file and out of the token store
        assertNull(h.tokens.read())
        assertFalse(AuthTestSupport.dataStoreDump(context).contains("legacy-rt"))
        assertEquals("legacy-rt", h.revoked.single().refreshToken)
        assertEquals(DropboxConfig.LEGACY_SENTINEL, h.revoked.single().appKey)
        assertEquals("dev-legacy1", deviceId())
        assertEquals(InstallStamp.LEGACY, h.stamp.read())

        // runs once
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(1, h.exports().size)
        assertEquals(1, h.revoked.size)
    }
}

/** WP-8.21 LegacyDisconnectedUpgradeTest (M1): only the keys clearDropbox() leaves behind. */
class LegacyDisconnectedUpgradeTest : StartupTestBase() {
    @Test fun disconnectedLegacy_isStillLegacy() = runBlocking {
        stampLegacy() // P5 (DEC-13): stamp-less + DEVICE_ID is a restore now; see seedLinkedLegacy
        h.ds.saveLocalDeviceId("dev-legacy2")
        h.ds.saveEvtFileRevs(mapOf("f.json" to "r1"))
        h.ds.saveEvtLastUploadTs(5L)
        h.ds.saveEvtLastPullAt(6L)
        h.ds.saveLocalSavedAt(7L)
        insertTasks(2)

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        assertEquals("2:marker=${DropboxConfig.LEGACY_SENTINEL}", h.traces[1])
        assertEquals(1, h.exports().size)
        val p = h.p()
        assertNull(p[SyncDataStore.EVT_FILE_REVS])
        assertNull(p[SyncDataStore.EVT_LAST_UPLOAD_TS])
        assertNull(p[SyncDataStore.EVT_LAST_PULL_AT])
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        assertEquals("dev-legacy2", deviceId())
        assertTrue(h.revoked.isEmpty())
    }

    @Test fun onlyTheDatabaseExists_isLegacy() = runBlocking {
        assertTrue(h.launch(dbExisted = true, dsHadKeys = false))
        assertEquals("2:marker=${DropboxConfig.LEGACY_SENTINEL}", h.traces[1])
        assertEquals(1, h.exports().size)
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
    }
}

/** WP-8.21 PostMigrationStateTest (m6). */
class PostMigrationStateTest : StartupTestBase() {
    @Test fun afterSwitch_disabled_notLinked_noMonthlyBackups() = runBlocking {
        seedLinkedLegacy()
        h.ds.saveAuthState(AuthState.LINKED)
        assertTrue(h.ds.isDropboxEnabled.first())

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        assertFalse(h.ds.isDropboxEnabled.first())
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
        assertEquals(emptyList<String>(), h.ds.monthlyBackups.first())
        assertEquals("[]", h.p()[SyncDataStore.MONTHLY_BACKUPS])
    }
}

/** WP-8.21 MarkerSurvivesTokenClearTest (D16). */
class MarkerSurvivesTokenClearTest : StartupTestBase() {
    private val scratch = "dbid:scratch-1"
    private val counts = """{"2026-09":{"n":2}}"""

    private suspend fun linkedFreshDevice() {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        h.tokens.write(TokenSet("at", "rt", Long.MAX_VALUE, scratch))
        h.ds.markLinked(scratch)
        h.baseStore.writeAtomic(DropboxConfigRule.TEST_APP_KEY, scratch, "rev-1", "{}")
        h.prefs.edit { it[SyncKeys.EVT_FILE_COUNTS] = counts }
    }

    private suspend fun assertNoRerun() {
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertTrue(h.exports().isEmpty())
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        assertEquals(counts, h.p()[SyncKeys.EVT_FILE_COUNTS])
        assertNotNull("no first-connect choice: the base stays valid", h.baseStore.validFor(DropboxConfigRule.TEST_APP_KEY, scratch))
    }

    @Test fun disconnect_thenRelaunch_noRerun() = runBlocking {
        linkedFreshDevice()
        h.manager.disconnect()
        assertNull(h.tokens.read())
        assertNoRerun()
    }

    @Test fun invalidGrant_thenRelaunch_noRerun() = runBlocking {
        linkedFreshDevice()
        h.ds.markReconnectRequired()
        assertNoRerun()
        assertEquals(AuthState.RECONNECT_REQUIRED, h.ds.authState.first())
    }

    @Test fun interlockRefusal_thenRelaunch_noRerun() = runBlocking {
        linkedFreshDevice()
        InterlockPolicy.unlocked = false
        InterlockPolicy.allowlist = setOf("dbid:someone-else")
        h.manager.getAuthorizationUri()
        h.endpoint.tokenAnswers.addLast(
            FakeTokenEndpoint.Canned(200, """{"access_token":"a2","refresh_token":"r2","expires_in":14400,"account_id":"dbid:refused","token_type":"bearer"}""")
        )
        assertFalse(h.manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNoRerun()
    }
}

/** WP-8.21 MigrationFailureTest: a throwing step keeps the barrier closed. */
class MigrationFailureTest : StartupTestBase() {
    @Test fun eachStepFailure_barrierClosed_statusFailed() = runBlocking {
        for (step in listOf("db", "tokens", "base", "appKey", "export")) {
            h.reset()
            h.stamp.write(InstallStamp.LEGACY)
            h.barrierOpened = 0
            seedLinkedLegacy()
            h.failOnce(step)
            assertFalse(step, h.launch(dbExisted = true, dsHadKeys = true))
            assertEquals(step, 0, h.barrierOpened)
            assertEquals(step, StartupMigrations.STATUS_FAILED, h.lastStatus)
            assertEquals(step, StartupMigrations.STATUS_FAILED, h.p()[SyncKeys.SYNC_LAST_ERROR])
            assertNotEquals(step, DropboxConfigRule.TEST_APP_KEY, marker())
        }
        // the next launch succeeds and clears the stale status
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(1, h.barrierOpened)
        assertNull(h.p()[SyncKeys.SYNC_LAST_ERROR])
    }

    @Test fun realObject_publishesStatus() {
        try {
            StartupMigrations.publish(StartupMigrations.STATUS_FAILED)
            assertEquals(StartupMigrations.STATUS_FAILED, StartupMigrations.status.value)
        } finally {
            StartupMigrations.resetStatusForTest() // review 46 F6: no global state left behind
        }
    }
}

/** WP-8.21 CrashMidMigrationTest: a crash between any two steps resumes to the same end state. */
class CrashMidMigrationTest : StartupTestBase() {
    private suspend fun assertLegacyEndState(label: String) {
        assertEquals(label, DropboxConfigRule.TEST_APP_KEY, marker())
        assertEquals(label, DropboxConfig.LEGACY_SENTINEL, h.baseStore.read()?.appKey)
        assertEquals(label, "rev-legacy", h.baseStore.read()?.rev)
        assertNull(label, h.tokens.read())
        assertNull(label, h.ds.dropboxRefreshToken.first())
        assertNull(label, h.p()[SyncKeys.EVT_FILE_COUNTS])
        assertNull(label, h.p()[SyncDataStore.EVT_FILE_REVS])
        assertFalse(label, h.ds.isDropboxEnabled.first())
        assertEquals(label, 3, taskCount())
        assertEquals(label, "dev-legacy1", deviceId())
        assertTrue(label, h.exports().isNotEmpty())
        assertEquals(label, 1, h.barrierOpened)
    }

    @Test fun legacy_crashAtEachStep_resumes() = runBlocking {
        for (step in listOf("db", "tokens", "base", "export", "appKey")) {
            h.close()
            h = StartupHarness(context)
            h.reset()
            seedLinkedLegacy()
            h.failOnce(step)
            assertFalse(step, h.launch(dbExisted = true, dsHadKeys = true))
            // the relaunch sees the DB and the DataStore, as a real restart would
            assertTrue(step, h.launch(dbExisted = true, dsHadKeys = true))
            assertLegacyEndState(step)
            assertEquals(step, "legacy-rt", h.revoked.single().refreshToken)
        }
    }

    @Test fun legacy_crashBeforeTheFinalMarkerWrite_rerunsSafely() = runBlocking {
        seedLinkedLegacy()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        // state of a crash after the clearing edit, before `APP_KEY_OF_DATA := APP_KEY`
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfig.LEGACY_SENTINEL }
        h.barrierOpened = 0
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertLegacyEndState("final-marker")
        assertEquals("a re-run exports again", 2, h.exports().size)
    }

    @Test fun fresh_crashAfterDbOpen_staysFresh() = runBlocking {
        h.failOnce("tokens")
        assertFalse(h.launch(dbExisted = false, dsHadKeys = false))
        // the DB file and the marker now exist: D21 alone would say "legacy"
        h.prefs.edit { it.remove(SyncKeys.APP_KEY_OF_DATA) }
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        assertTrue(h.exports().isEmpty())
    }

    @Test fun restore_crashDuringReset_resumes() = runBlocking {
        seedRestoredCopy()
        h.failOnce("base")
        assertFalse(h.launch(dbExisted = true, dsHadKeys = true))
        val idAfterReset = deviceId()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(InstallStamp.RESTORED, h.stamp.read())
        assertEquals("the reset finished, so it does not run again", idAfterReset, deviceId())
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
    }

    private suspend fun seedRestoredCopy() {
        h.prefs.edit {
            it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfigRule.TEST_APP_KEY
            it[SyncDataStore.DEVICE_ID] = "dev-oldphone"
            it[SyncKeys.ACCOUNT_ID] = "dbid:a"
            it[SyncKeys.AUTH_STATE] = AuthState.LINKED.name
            it[SyncDataStore.DROPBOX_ENABLED] = true
        }
    }
}

/** WP-8.21 guess G3: a copy restored from an Android backup on a new phone. */
class RestoreResetTest : StartupTestBase() {
    @Test fun restoredCopy_isUnlinked_getsANewDeviceId_keepsBaseAndMarker() = runBlocking {
        h.prefs.edit {
            it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfigRule.TEST_APP_KEY
            it[SyncDataStore.DEVICE_ID] = "dev-oldphone"
            it[SyncKeys.ACCOUNT_ID] = "dbid:a"
            it[SyncKeys.AUTH_STATE] = AuthState.LINKED.name
            it[SyncDataStore.DROPBOX_ENABLED] = true
            it[SyncKeys.EVT_FILE_COUNTS] = "{}"
            it[SyncKeys.EVT_PUSH_BLOCKED] = true
            it[SyncKeys.PENDING_PURGE_DEV] = "dev-oldphone"
            it[SyncDataStore.EVT_LAST_UPLOAD_TS] = 9L
            it[SyncDataStore.HLC_LAST_ISSUED] = 42L
        }
        h.baseStore.writeAtomic(DropboxConfigRule.TEST_APP_KEY, "dbid:a", "rev-9", "{}")
        insertTasks(2)

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        val id = deviceId()!!
        assertNotEquals("dev-oldphone", id)
        assertTrue(id.startsWith("dev-"))
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
        assertNull(h.ds.getAccountId())
        assertFalse(h.ds.isDropboxEnabled.first())
        val p = h.p()
        assertNull(p[SyncKeys.EVT_FILE_COUNTS])
        assertNull(p[SyncKeys.EVT_PUSH_BLOCKED])
        assertNull(p[SyncKeys.PENDING_PURGE_DEV])
        assertNull(p[SyncDataStore.EVT_LAST_UPLOAD_TS])
        // Kept, not cleared. Since WP-8.6 a local save (insertTasks) stamps the process HLC, which
        // may persist a later value when it is bound to this DataStore (test-order dependent).
        assertTrue((p[SyncDataStore.HLC_LAST_ISSUED] ?: 0L) >= 42L)
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
        assertNotNull(h.baseStore.validFor(DropboxConfigRule.TEST_APP_KEY, "dbid:a"))
        assertEquals(2, taskCount())
        assertTrue(h.exports().isEmpty())
        assertEquals(InstallStamp.RESTORED, h.stamp.read())

        // the same device relaunching keeps its new id
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(id, deviceId())
    }

    @Test fun sameDeviceRelaunch_neverResets() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        h.prefs.edit { it[SyncDataStore.DEVICE_ID] = "dev-mine" }
        h.ds.markLinked("dbid:a")
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals("dev-mine", deviceId())
        assertEquals(AuthState.LINKED, h.ds.authState.first())
    }
}

/** WP-8.21: disconnect before TokenMigration revokes the legacy plaintext grant (24_review_wp81 gap). */
class DisconnectLegacyGrantTest : StartupTestBase() {
    @Test fun disconnect_beforeTokenMigration_revokesThePlaintextGrant() = runBlocking {
        h.ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        assertTrue(h.manager.disconnect())
        assertEquals("Bearer legacy-at", h.endpoint.revokeCalls().single().header("Authorization"))
        assertFalse(AuthTestSupport.dataStoreDump(context).contains("legacy-rt"))
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
    }
}

/** The app key migration gate: what runs for each combination of stored key and marker. */
class AppKeyMigrationGateTest : StartupTestBase() {
    private val grant = TokenSet("at", "rt", Long.MAX_VALUE, "acc", "old")

    private suspend fun setUp(key: String, markerValue: String?) {
        DropboxConfig.setCacheForTests(key)
        h.prefs.edit { p ->
            if (markerValue == null) p.remove(SyncKeys.APP_KEY_OF_DATA) else p[SyncKeys.APP_KEY_OF_DATA] = markerValue
            p[SyncDataStore.MONTHLY_BACKUPS] = """["backup-2026-08.json"]"""
            p[SyncDataStore.EVT_LAST_PULL_AT] = 222L
        }
        h.tokens.write(grant)
    }

    private suspend fun assertUntouched(expectedMarker: String?) {
        assertEquals(expectedMarker, marker())
        assertTrue(h.exports().isEmpty())
        assertEquals(grant, h.tokens.read())
        assertEquals("""["backup-2026-08.json"]""", h.p()[SyncDataStore.MONTHLY_BACKUPS])
        assertEquals(222L, h.p()[SyncDataStore.EVT_LAST_PULL_AT])
    }

    private suspend fun assertFullReset(expectedMarker: String) {
        assertEquals(expectedMarker, marker())
        assertEquals(1, h.exports().size)
        assertNull(h.tokens.read())
        assertFalse(h.ds.isDropboxEnabled.first())
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
        assertEquals("[]", h.p()[SyncDataStore.MONTHLY_BACKUPS])
    }

    @Test fun `no key stored does nothing`() = runBlocking {
        setUp("", null)
        assertFalse(h.appKeyMigration().runIfNeeded())
        assertUntouched(null)
    }

    @Test fun `first key with no marker seeds without reset`() = runBlocking {
        setUp("k", null)
        assertFalse(h.appKeyMigration().runIfNeeded())
        assertUntouched("k")
    }

    @Test fun `same key is a no-op`() = runBlocking {
        setUp("k", "k")
        assertFalse(h.appKeyMigration().runIfNeeded())
        assertUntouched("k")
    }

    @Test fun `changed key runs the full reset`() = runBlocking {
        setUp("new", "old")
        assertTrue(h.appKeyMigration().runIfNeeded())
        assertFullReset("new")
    }

    @Test fun `sentinel marker gets the full reset`() = runBlocking {
        setUp("k", DropboxConfig.LEGACY_SENTINEL)
        assertTrue(h.appKeyMigration().runIfNeeded())
        assertFullReset("k")
    }

    @Test fun `cleared key is a no-op`() = runBlocking {
        setUp("", "old")
        assertFalse(h.appKeyMigration().runIfNeeded())
        assertUntouched("old")
    }
}

/** Startup applies the build seed once, refreshes the runtime key, then writes the marker. */
class AppKeySeedStartupTest : StartupTestBase() {
    private suspend fun storedKey(): String? = h.p()[SyncKeys.DROPBOX_APP_KEY]

    private suspend fun noStoredKey() = h.ds.saveDropboxAppKey("")

    @Test fun `FRESH with seed k`() = runBlocking {
        noStoredKey()
        h.appKeySeed = "k"
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals("k", storedKey())
        assertEquals("k", DropboxConfig.appKey())
        assertEquals("k", marker())
        assertTrue(h.exports().isEmpty())
    }

    @Test fun `FRESH with no seed`() = runBlocking {
        noStoredKey()
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertNull(storedKey())
        assertNull(marker())
        assertEquals("", DropboxConfig.appKey())
    }

    @Test fun `seed is not re-applied after the user cleared the key`() = runBlocking {
        noStoredKey()
        h.appKeySeed = "k"
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        h.ds.saveDropboxAppKey("")
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertNull(storedKey())
        assertEquals("", DropboxConfig.appKey())
    }

    @Test fun `existing stored key wins over the seed`() = runBlocking {
        h.ds.saveDropboxAppKey("mine")
        h.appKeySeed = "k"
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals("mine", storedKey())
        assertEquals("mine", DropboxConfig.appKey())
    }

    @Test fun `LEGACY with no marker writes LEGACY_SENTINEL`() = runBlocking {
        noStoredKey()
        stampLegacy()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(DropboxConfig.LEGACY_SENTINEL, marker())
    }

    @Test fun `RESTORE_PENDING with no marker writes LEGACY_SENTINEL`() = runBlocking {
        noStoredKey()
        h.stamp.write(InstallStamp.RESTORE_PENDING)
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(DropboxConfig.LEGACY_SENTINEL, marker())
    }

    @Test fun `maintainer upgrade`() = runBlocking {
        noStoredKey()
        stampLegacy()
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "k" }
        val grant = TokenSet("at", "rt", Long.MAX_VALUE, "acc", "k")
        h.tokens.write(grant)
        h.appKeySeed = "k"
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertTrue(h.exports().isEmpty())
        assertEquals(grant, h.tokens.read())
        assertEquals("k", marker())
        assertFalse(h.tokens.read()!!.isLegacyAppKey)
    }

    @Test fun `a failing step 1 still leaves the cache filled`() = runBlocking {
        h.ds.saveDropboxAppKey("mine")
        DropboxConfig.setCacheForTests("")
        h.failOnce("db")
        assertFalse(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals("mine", DropboxConfig.appKey())
    }
}

/** The real boot under Robolectric must not store any key: the test app has an empty seed. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = com.example.BootingTestQuestaApplication::class)
class AppKeySeedTestAppTest {
    @Test fun `test app boot stores no app key`() = runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<com.example.QuestaApplication>()
        app.startupMigrationsJob?.join()
        try {
            assertEquals("", SyncDataStore(app).dropboxAppKey())
        } finally {
            DropboxConfig.store = null
            DropboxConfig.setCacheForTests("")
        }
    }
}
