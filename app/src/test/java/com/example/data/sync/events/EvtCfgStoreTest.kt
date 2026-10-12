package com.example.data.sync.events

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.7 (§4.3) -- the DataStore home of the evt cfg: round trips (JSON null revs kept), the
 * EVT_PUSH_BLOCKED flag semantics (a restore reset that removes the flag also clears the map),
 * and PENDING_PURGE_DEV read-and-remove (PendingPurgeConsumedTest's store half).
 */
class EvtCfgStoreTest {
    /**
     * In-memory Preferences DataStore: a file-backed one cannot rename over its own file twice on
     * a Windows JVM (no Robolectric here), and the store logic is what is under test.
     */
    private class MemPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val prefs: DataStore<Preferences> = MemPrefs()
    private val store = DataStoreEvtCfgStore(prefs)

    @Test
    fun defaults() = runBlocking {
        assertEquals(EvtCfg(), store.read())
        assertNull(store.takePendingPurgeDev())
    }

    @Test
    fun pushAndPull_roundTrip() = runBlocking {
        val fc = mapOf("dev-me-202609.json" to buildJsonObject { put("count", JsonPrimitive(2)); put("hash", JsonPrimitive("h")) })
        val pb = mapOf("dev-me-202608.json" to buildJsonObject { put("at", JsonPrimitive(1)); put("local", JsonPrimitive(1)); put("known", JsonPrimitive(3)) })
        store.savePush(123L, fc, pb)
        store.savePull(mapOf("a.json" to JsonPrimitive("r1"), "b.json" to JsonNull), mapOf("c.json" to EvtPullHealer.badRev(null, 5)), 7L, 8L)
        val c = store.read()
        assertEquals(123L, c.fullPushAt)
        assertEquals(fc, c.fileCounts)
        assertEquals(pb, c.pushBlocked)
        assertEquals(mapOf("a.json" to JsonPrimitive("r1"), "b.json" to JsonNull), c.fileRevs)
        assertEquals(mapOf("c.json" to EvtPullHealer.badRev(null, 5)), c.badRevs)
        assertEquals(7L, c.lastPullAt)
        assertEquals(8L, c.fullScanAt)
        val p = prefs.data.first()
        assertEquals(true, p[SyncKeys.EVT_PUSH_BLOCKED])
        assertEquals(7L, p[SyncDataStore.EVT_LAST_PULL_AT])
    }

    @Test
    fun pushBlockedMap_isReadOnlyWhileTheFlagIsSet() = runBlocking {
        val pb = mapOf("dev-old-202609.json" to buildJsonObject { put("at", JsonPrimitive(1)) })
        store.savePush(1L, emptyMap(), pb)
        // WP-8.21's restore reset removes EVT_PUSH_BLOCKED (StartupMigrations); the detail map goes with it.
        prefs.edit { it.remove(SyncKeys.EVT_PUSH_BLOCKED) }
        assertTrue(store.read().pushBlocked.isEmpty())
        store.savePush(1L, emptyMap(), emptyMap())
        assertEquals(false, prefs.data.first()[SyncKeys.EVT_PUSH_BLOCKED])
    }

    @Test
    fun pendingPurge_setThenTake_removesTheKey() = runBlocking {
        store.setPendingPurgeDev("dev-old")
        assertEquals("dev-old", prefs.data.first()[SyncKeys.PENDING_PURGE_DEV])
        assertEquals("dev-old", store.takePendingPurgeDev())
        assertNull(prefs.data.first()[SyncKeys.PENDING_PURGE_DEV])
        assertNull(store.takePendingPurgeDev())
    }
}
