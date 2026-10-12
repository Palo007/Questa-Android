package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.InterlockPolicy
import com.example.data.sync.auth.InterlockRefused
import com.example.data.sync.auth.TokenSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.I (W4, orchestrator item 7): `SyncManager.activeAccessTokenChecked` (D7 first, the round's
 * token path) and the three token-bound event ops wired from it (forced pull/push, reset purge).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenBoundEventOpsTest : Wp89TestBase() {

    private class Rig(val d: Wp89Device, context: android.content.Context) {
        val wiring = AppSyncWiring(context, d.db, d.repository, d.syncDataStore)
        val token: suspend () -> String? = { d.manager.activeAccessTokenChecked() }
        val force = ForceOpsEngine(
            internals = d.manager,
            syncDataStore = d.syncDataStore,
            localSubset = { d.manager.buildLocalSnapshot() },
            isConfigured = { d.syncDataStore.isDropboxEnabled.first() && d.tokens.read()?.refreshToken?.isNotEmpty() == true },
            eventsForcePull = wiring.forcePullEvents(token),
            eventsForcePush = wiring.forcePushEvents(token),
            backupPrefs = d.backupPrefs,
        )
    }

    private fun rig() = Rig(device89(), context)

    @Test fun nonAllowlistedAccount_isRefused_withZeroRequests() = runBlocking {
        val r = rig()
        r.d.link()
        InterlockPolicy.allowlist = emptySet()
        try {
            r.d.manager.activeAccessTokenChecked()
            fail("expected InterlockRefused")
        } catch (e: InterlockRefused) {
        }
        assertEquals(SyncResult.InterlockRefused, r.force.forcePushEvents())
        try { r.wiring.purgeOwnEvents(r.token)("dev-a") } catch (e: InterlockRefused) { }
        assertEquals(0, fake.requests.size)
    }

    @Test fun legacyKeyToken_isReconnectRequired_withZeroRequests() = runBlocking {
        val r = rig()
        r.d.link()
        r.d.tokens.write(TokenSet("legacy-at", "legacy-rt", System.currentTimeMillis() + 3_600_000L,
            FakeDropboxServer.SCRATCH_ACCOUNT_ID, appKey = DropboxConfig.LEGACY_SENTINEL))
        assertNull(r.d.manager.activeAccessTokenChecked())
        assertEquals(AuthState.RECONNECT_REQUIRED, r.d.syncDataStore.authState.first())
        assertEquals(0, fake.requests.size)
    }

    @Test fun forcedEventPush_uploadsOwnMonth_onTheAllowlistedFake() = runBlocking {
        val r = rig()
        r.d.link()
        val ts = System.currentTimeMillis() - 1000
        r.d.repository.insertLog(EventLogEntity(timestamp = ts, kind = "habitTap", taskId = "t1", direction = 1))
        assertEquals(SyncResult.Ok, r.force.forcePushEvents())
        val own = fake.eventFiles["dev-a-${EventSyncEngine.evtMonthKey(ts)}.json"]!!
        assertEquals("dev-a", Json.parseToJsonElement(own.content).jsonArray[0].let {
            (it as kotlinx.serialization.json.JsonObject)["dev"]!!.jsonPrimitive.content
        })
        val uploads = fake.requests.filter { it.endpoint == "/2/files/upload" }
        assertTrue(uploads.isNotEmpty())
        assertEquals(setOf("access-dev-a"), fake.requests.map { it.bearer }.toSet())
    }

    @Test fun resetPurge_deletesOnlyThisDevicesOwnEventFiles() = runBlocking {
        val r = rig()
        r.d.link()
        listOf("dev-a-202609.json", "dev-a-202608.json", "dev-a-2-202609.json", "dev-b-202609.json",
            "dev-a-202609 (1).json", "x-dev-a-202609.json").forEach { fake.seedEventFile(it, "[]") }
        r.wiring.purgeOwnEvents(r.token)("dev-a")
        val deleted = fake.requests.filter { it.endpoint == "/2/files/delete_v2" }
            .map { it.arg!!["path"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("/events/dev-a-202609.json", "/events/dev-a-202608.json"), deleted)
    }
}
