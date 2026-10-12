package com.example.startup

import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 (KT-43, KT-25): restore detection. The install stamp lives in `noBackupFilesDir`, so a
 * restored data copy arrives without it. A backup taken by a pre-WP-8.21 build has no
 * APP_KEY_OF_DATA marker either, but it does carry the old phone's DEVICE_ID.
 * Guess G-W2-1: stamp absent + DEVICE_ID present = restore (cost: one relink on an in-place
 * upgrade from a pre-WP-8.21 build).
 */
class RestoreDetectionTest : StartupTestBase() {

    /** KT-43: the failing case before P5 (classified LEGACY, old device id and tokens kept). */
    @Test fun markerlessDataStoreWithDeviceId_noStamp_isRestorePending() = runBlocking {
        h.ds.saveLocalDeviceId("dev-oldphone")
        h.ds.saveTokens("old-at", "old-rt", 3600L)
        h.prefs.edit {
            it[SyncKeys.ACCOUNT_ID] = "dbid:a"
            it[SyncKeys.AUTH_STATE] = AuthState.LINKED.name
            it[SyncDataStore.DROPBOX_ENABLED] = true
        }
        assertNull(marker())
        assertNull(h.stamp.read())

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        assertEquals(InstallStamp.RESTORED, h.stamp.read())
        assertTrue("2c:restore" in h.traces)
        val id = deviceId()!!
        assertNotEquals("dev-oldphone", id)
        assertTrue(id.startsWith("dev-"))
        assertNull("secure tokens cleared", h.tokens.read())
        val p = h.p()
        assertNull(p[SyncDataStore.DROPBOX_ACCESS_TOKEN])
        assertNull(p[SyncDataStore.DROPBOX_REFRESH_TOKEN])
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
        assertFalse(h.ds.isDropboxEnabled.first())

        // relaunch on the same phone: the stamp is there now, no second reset
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(id, deviceId())
    }

    /** KT-25 guard (passes before P5): a marked restored copy gets a new identity, tokens gone. */
    @Test fun restorePending_resetsIdentity() = runBlocking {
        h.prefs.edit {
            it[SyncKeys.APP_KEY_OF_DATA] = com.example.data.sync.auth.DropboxConfigRule.TEST_APP_KEY
            it[SyncDataStore.DEVICE_ID] = "dev-oldphone"
            it[SyncKeys.ACCOUNT_ID] = "dbid:a"
            it[SyncKeys.AUTH_STATE] = AuthState.LINKED.name
            it[SyncDataStore.DROPBOX_ENABLED] = true
        }
        h.ds.saveTokens("old-at", "old-rt", 3600L)

        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))

        assertEquals(InstallStamp.RESTORED, h.stamp.read())
        assertNotEquals("dev-oldphone", deviceId())
        assertNull(h.tokens.read())
        assertNull(h.p()[SyncDataStore.DROPBOX_REFRESH_TOKEN])
        assertEquals(AuthState.NOT_LINKED, h.ds.authState.first())
        assertEquals(com.example.data.sync.auth.DropboxConfigRule.TEST_APP_KEY, marker())
    }

    /** A legacy DataStore without a device id (never synced) stays LEGACY (D21). */
    @Test fun markerlessDataStoreWithoutDeviceId_noStamp_staysLegacy() = runBlocking {
        h.prefs.edit { it[SyncKeys.EVT_BAD_REVS] = "{}" }
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(InstallStamp.LEGACY, h.stamp.read())
    }
}
