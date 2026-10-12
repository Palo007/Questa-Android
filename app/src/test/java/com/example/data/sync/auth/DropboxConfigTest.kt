package com.example.data.sync.auth

import androidx.test.core.app.ApplicationProvider
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropboxConfigTest {
    @get:Rule val config = DropboxConfigRule(key = "")
    private lateinit var ds: SyncDataStore

    @Before fun setUp() = runBlocking {
        ds = SyncDataStore(ApplicationProvider.getApplicationContext())
        ds.saveDropboxAppKey("")          // clear whatever startup seeded
        DropboxConfig.store = ds
    }

    @Test fun `appKey is empty when nothing was ever entered`() = runBlocking {
        DropboxConfig.refresh()
        assertEquals("", DropboxConfig.appKey())
        assertEquals(false, DropboxConfig.isConfigured)
    }

    @Test fun `refresh reads the stored key`() = runBlocking {
        ds.saveDropboxAppKey("a-real-app-key")
        DropboxConfig.refresh()
        assertEquals("a-real-app-key", DropboxConfig.appKey())
        assertEquals(true, DropboxConfig.isConfigured)
    }

    @Test fun `refresh picks up a changed key`() = runBlocking {
        ds.saveDropboxAppKey("first"); DropboxConfig.refresh()
        ds.saveDropboxAppKey("second"); DropboxConfig.refresh()
        assertEquals("second", DropboxConfig.appKey())
    }

    @Test fun `saving an empty key removes it`() = runBlocking {
        ds.saveDropboxAppKey("first"); DropboxConfig.refresh()
        ds.saveDropboxAppKey(""); DropboxConfig.refresh()
        assertEquals("", DropboxConfig.appKey())
        assertEquals(false, DropboxConfig.isConfigured)
    }

    @Test fun `refresh with no store leaves the cache empty`() = runBlocking {
        DropboxConfig.store = null
        DropboxConfig.refresh()
        assertEquals("", DropboxConfig.appKey())
    }
}
