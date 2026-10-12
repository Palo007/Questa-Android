package com.example.ui.settings

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.QuestaApplication
import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.events.DiagRing
import com.example.data.sync.syncDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.14-R1: the diagnostics bundle (Opti `questaFullDiagnostic`) never carries a secret. */
class DiagnosticsExportTest {

    private val access = "sl.u.AAAAaccessSECRETvalue123"
    private val refresh = "RefreshSECRETvalue987654"
    private val verifier = "pkceVerifierSECRET555"
    private val account = "dbid:AABBCCsecretAccount"
    private val secrets = listOf(access, refresh, verifier, account)

    private fun leaky() = DiagnosticsCapture(
        DiagnosticsSnapshot(
            generatedAt = 1_758_800_000_123L,
            appVersion = "v1.0", authState = "LINKED",
            refreshTokenPresent = true, accessTokenPresent = true,
            lastSyncError = "HTTP 401 Authorization: Bearer $access",
            lastBackupError = "upload failed for $account",
            deviceId = "dev-38ab12cd", deviceName = "phone $refresh",
            baseRev = "015abc", appKeyOfData = "legacy-app-key-under-test",
            rowCounts = mapOf("tasks" to 3),
            diagRing = listOf(
                buildJsonObject { put("t", 1); put("kind", "evtPullSkip"); put("reason", "token $verifier") },
                buildJsonObject { put("t", 2); put("kind", "error"); put("accessToken", access); put("msg", "sl.otherToken_xyz dbid:ZZZ") },
            ),
        ),
        secrets,
    )

    private fun assertClean(text: String) {
        for (needle in listOf("sl.", "dbid:", "Bearer") + secrets) {
            assertFalse("export contains '$needle'", text.contains(needle, ignoreCase = needle == "Bearer"))
        }
    }

    @Test fun export_hasNoTokensVerifierAccountOrBearer() {
        val text = Diagnostics.exportText(leaky())
        assertClean(text)
        val o = Json.parseToJsonElement(text).jsonObject
        // Opti keeps presence only.
        assertEquals("true", o["sync"]!!.jsonObject["refreshToken"]!!.jsonPrimitive.content)
        assertEquals("true", o["sync"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content)
        // A secret-named key in the ring keeps only a boolean.
        val ring = o["errors"]!! as kotlinx.serialization.json.JsonArray
        assertEquals(JsonPrimitive(true), (ring[1] as JsonObject)["accessToken"])
        assertEquals("LINKED", o["sync"]!!.jsonObject["authState"]!!.jsonPrimitive.content)
        assertEquals("dev-38ab12cd", o["sync"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
    }

    @Test fun screenRows_andRing_areScrubbedToo() {
        val c = leaky()
        assertClean(Diagnostics.rows(c).joinToString("\n") { it.first + "=" + it.second })
        val ring = Diagnostics.ringNewestFirst(c)
        assertClean(ring.joinToString("\n"))
        assertTrue("newest first", ring[0].contains("\"t\":2"))
    }

    @Test fun optiBundleShape() {
        val o = Json.parseToJsonElement(Diagnostics.exportText(leaky())).jsonObject
        for (k in listOf("manifest", "meta", "errors", "sync", "room", "events", "inbox", "pendingTaps", "reminders", "backup")) {
            assertTrue("missing $k", k in o)
        }
        assertEquals(1, o["manifest"]!!.jsonObject["schemaVersion"]!!.jsonPrimitive.content.toInt())
    }

    /** WP-8.22-C: no legacy `lastRev` (DROPBOX_LAST_REV is always null), baseRev and clock.offsetMs stay, still no secret. */
    @Test fun wp822c_noLegacyLastRev_keepsBaseRevAndClockOffset_noSecrets() {
        val c = leaky().let { DiagnosticsCapture(it.snapshot.copy(clockOffsetMs = 86_400_000L), it.secrets) }
        val text = Diagnostics.exportText(c)
        assertClean(text)
        val o = Json.parseToJsonElement(text).jsonObject
        val sync = o["sync"]!!.jsonObject
        assertFalse("legacy lastRev is gone", "lastRev" in sync)
        assertFalse(text.contains("\"lastRev\""))
        assertEquals("015abc", sync["baseRev"]!!.jsonPrimitive.content)
        assertEquals(86_400_000L, o["clock"]!!.jsonObject["offsetMs"]!!.jsonPrimitive.content.toLong())
        assertFalse(Diagnostics.rows(c).any { it.first == "sync.lastRev" })
        assertTrue(Diagnostics.rows(c).any { it.first == "sync.baseRev" && it.second == "015abc" })
    }

    /** WP-8.22-C: sync.deviceName is what Settings shows: the stored name, else the Settings placeholder (id's first 6). */
    @Test fun wp822c_deviceName_matchesSettings() {
        val id = "dev-38ab12cd"
        val devs = listOf(DeviceEntity(id = "other", name = "tablet"), DeviceEntity(id = id, name = "Pixel"))
        assertEquals("Pixel", Diagnostics.shownDeviceName(devs, id))
        assertEquals("dev-38", Diagnostics.shownDeviceName(listOf(DeviceEntity(id = id, name = "")), id))
        assertEquals("dev-38", Diagnostics.shownDeviceName(emptyList(), id))
        assertEquals(SettingsUiState(deviceId = id).deviceShort, Diagnostics.shownDeviceName(emptyList(), id))
        assertEquals("", Diagnostics.shownDeviceName(emptyList(), ""))
    }

    @Test fun fileName_isOptiStyle() {
        assertEquals("questa-fulldiag-2025-09-25T11-33-20-123Z.json", Diagnostics.fileName(1_758_800_000_123L))
        assertEquals("questa-fulldiag-2025-09-25T11-33-20-000Z.json", Diagnostics.fileName(1_758_800_000_000L))
    }
}

/** The production reader over the app's real stores: every stored token value stays out of the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsReaderTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>() as QuestaApplication

    @Before fun setUp() = AppBootQuiesce.quiesce()
    @After fun tearDown() {
        DiagRing.process.clear()
        AppBootQuiesce.quiesce()
    }

    @Test fun reader_readsTheStores_andTheExportHasNoStoredSecret() = runBlocking {
        val access = "sl.u.StoredAccessTOKEN0001"
        val refresh = "StoredRefreshTOKEN0002"
        val verifier = "StoredPkceVERIFIER0003"
        val account = "dbid:StoredAccountID0004"
        app.applicationContext.syncDataStore.edit {
            it[SyncDataStore.DROPBOX_ACCESS_TOKEN] = access
            it[SyncDataStore.DROPBOX_REFRESH_TOKEN] = refresh
            it[SyncDataStore.OAUTH_VERIFIER] = verifier
            it[SyncKeys.ACCOUNT_ID] = account
            it[SyncKeys.SYNC_LAST_ERROR] = "401 Bearer $access"
            it[SyncKeys.APP_KEY_OF_DATA] = "legacy-app-key-under-test"
            it[SyncKeys.PENDING_TAPS] = "[{\"a\":1},{\"a\":2}]"
            it[SyncKeys.INBOX_HABITS_HASH] = "h123"
        }
        DiagRing.process.push("evtPullSkip", mapOf("reason" to DiagRing.str("refresh=$refresh")))

        val c = DiagnosticsReader(app).read(nowMs = 1_758_800_000_000L, autoBackupSummary = "Daily")
        val s = c.snapshot
        assertEquals(2, s.pendingTaps)
        assertEquals("legacy-app-key-under-test", s.appKeyOfData)
        assertEquals("h123", s.inboxHabitsHash)
        assertTrue(s.refreshTokenPresent && s.accessTokenPresent)
        assertEquals(8, s.roomVersion) // CR-KT-015: schema v8
        assertTrue("row counts from Room", s.rowCounts.isNotEmpty())
        assertEquals("Daily", s.autoBackupTiers)
        assertEquals(1, s.diagRing.size)

        // WP-8.22-C: no stored name -> the Settings placeholder, never empty.
        assertEquals(s.deviceId.take(6), s.deviceName)
        assertTrue(s.deviceName.isNotEmpty())
        app.repository.setDeviceName("dev-phone")
        assertEquals("dev-phone", DiagnosticsReader(app).read(1_758_800_000_000L, "Daily").snapshot.deviceName)

        val text = Diagnostics.exportText(c)
        for (needle in listOf("sl.", "dbid:", "Bearer", access, refresh, verifier, account)) {
            assertFalse("export contains '$needle'", text.contains(needle))
        }
    }
}
