package com.example.data.sync.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/**
 * WP-8.1 -- PX-09. The real AES-256-GCM path of [AndroidKeystoreTokenStore], with a software
 * key standing in for the Android Keystore (Robolectric has no AndroidKeyStore provider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureTokenStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sample = TokenSet("sl.ACCESS-abc123", "REFRESH-xyz789", 1_700_000_000_000L, "dbid:scratch")

    private fun store(k: javax.crypto.SecretKey = key) = AndroidKeystoreTokenStore(context) { k }

    // 8.I: the app's own StartupMigrations.run reads and clears this token file; let it settle first.
    @org.junit.Before fun awaitAppStartup() = runBlocking {
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    private fun prefsFileText(): String {
        val f = File(context.applicationInfo.dataDir, "shared_prefs/${AndroidKeystoreTokenStore.PREFS_NAME}.xml")
        val onDisk = if (f.exists()) f.readText() else ""
        // Robolectric may keep prefs in memory only; the stored values are what would hit disk.
        val inPrefs = context.getSharedPreferences(AndroidKeystoreTokenStore.PREFS_NAME, Context.MODE_PRIVATE).all.toString()
        return onDisk + inPrefs
    }

    @Test fun inMemory_roundTrips() = runBlocking {
        val s = InMemorySecureTokenStore()
        assertNull(s.read())
        s.write(sample)
        assertEquals(sample, s.read())
        s.clear()
        assertNull(s.read())
    }

    @Test fun aesGcm_roundTrips_andClears() = runBlocking {
        val s = store()
        assertNull(s.read())
        s.write(sample)
        assertEquals(sample, s.read())
        assertEquals(sample, store().read()) // a second instance reads the same file
        s.clear()
        assertNull(s.read())
    }

    @Test fun nullAccountId_roundTrips() = runBlocking {
        val s = store()
        s.write(sample.copy(accountId = null))
        assertEquals(null, s.read()!!.accountId)
    }

    @Test fun backingFile_holdsNoPlaintextToken() = runBlocking {
        store().write(sample)
        val raw = prefsFileText()
        assertFalse(raw.isEmpty())
        assertFalse(raw.contains("ACCESS-abc123"))
        assertFalse(raw.contains("REFRESH-xyz789"))
        assertFalse(raw.contains("refreshToken"))
        assertFalse(raw.contains("dbid:scratch"))
    }

    @Test fun freshIvPerWrite() = runBlocking {
        store().write(sample)
        val a = prefsFileText()
        store().write(sample)
        assertNotEquals(a, prefsFileText())
    }

    @Test fun wrongKey_readsAsNoToken_neverThrows() = runBlocking {
        store().write(sample)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(store(other).read())
    }

    @Test fun decode_rejectsBlobWithoutRefreshToken() {
        assertNull(AndroidKeystoreTokenStore.decode("""{"accessToken":"a"}"""))
        assertEquals(sample, AndroidKeystoreTokenStore.decode(AndroidKeystoreTokenStore.encode(sample)))
    }
}
