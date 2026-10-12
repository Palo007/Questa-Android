package com.example.startup

import androidx.datastore.preferences.core.edit
import com.example.data.clock.StartupBarrier
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.DropboxConfigRule
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.auth.SecureTokenStore
import com.example.data.sync.auth.TokenSet
import com.example.seedFirstLaunch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.21 review fixes (25_review_wp821): C1 seed default, C2 revoke only a legacy-key grant,
 * leftover (a) the revoke is handed off before `tokens.clear()`.
 */
class Wp821ReviewFixesTest : StartupTestBase() {

    /** C1, inverted by 8.I at the W3 close: the run is wired, so the default waits on the barrier. */
    @Test fun seedDefault_waitsOnTheBarrier_onceRunIsWired() = runBlocking {
        // Held closed with MockK: WP-8.6 tests in the same JVM call the real one-shot markDone(),
        // so a "nothing opened it" precondition depended on test order (review 26 merge).
        io.mockk.mockkObject(StartupBarrier)
        try {
            io.mockk.coEvery { StartupBarrier.await() } coAnswers { kotlinx.coroutines.awaitCancellation() }
            assertNull(kotlinx.coroutines.withTimeoutOrNull(500) { seedFirstLaunch(h.repo, FirstLaunchSeedGate.forDir(h.noBackupDir)) })
        } finally {
            io.mockk.unmockkObject(StartupBarrier)
        }
        assertEquals(0, taskCount())
        assertTrue(h.repo.allRewards.first().isEmpty())
    }

    /** C2: a grant minted under the NEW key (e.g. the linked debug app) is never revoked. */
    @Test fun newKeyGrant_isNotRevoked_bySwitch() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfig.LEGACY_SENTINEL }
        h.tokens.write(TokenSet("new-at", "new-rt", Long.MAX_VALUE, "acc", DropboxConfigRule.TEST_APP_KEY))

        assertTrue(h.appKeyMigration().runIfNeeded())

        assertTrue("a new-key grant must never be revoked", h.revoked.isEmpty())
        // Card §5.0 step 4 clears unconditionally and sets AUTH_STATE := NOT_LINKED, so the local
        // copy is dropped (a kept token would contradict NOT_LINKED); the live grant survives.
        assertNull(h.tokens.read())
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
    }

    /** A grant from any app other than the current one is handed to the revoke callback. */
    @Test fun grantFromAnyOtherApp_isRevoked_bySwitch() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "old" }
        h.tokens.write(TokenSet("old-at", "old-rt", Long.MAX_VALUE, null, "some-old-app"))

        assertTrue(h.appKeyMigration().runIfNeeded())

        assertEquals("some-old-app", h.revoked.single().appKey)
        assertNull(h.tokens.read())
    }

    /** A grant under the current key is never revoked, whatever the old marker says. */
    @Test fun grantUnderCurrentKey_isNeverRevoked() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "old" }
        h.tokens.write(TokenSet("new-at", "new-rt", Long.MAX_VALUE, "acc", DropboxConfigRule.TEST_APP_KEY))

        assertTrue(h.appKeyMigration().runIfNeeded())

        assertTrue(h.revoked.isEmpty())
        assertNull(h.tokens.read())
    }

    /** C2: a grant under the legacy key is revoked. */
    @Test fun legacyKeyGrant_isRevoked_bySwitch() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfig.LEGACY_SENTINEL }
        h.tokens.write(TokenSet("old-at", "old-rt", Long.MAX_VALUE, null, DropboxConfig.LEGACY_SENTINEL))

        assertTrue(h.appKeyMigration().runIfNeeded())

        assertEquals("old-rt", h.revoked.single().refreshToken)
        assertNull(h.tokens.read())
    }

    /** Leftover (a): a crash inside tokens.clear() must not lose the revoke. */
    @Test fun crashDuringClear_revokeAlreadyHandedOff() = runBlocking {
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfig.LEGACY_SENTINEL }
        val inner = InMemorySecureTokenStore()
        inner.write(TokenSet("old-at", "old-rt", Long.MAX_VALUE, null, DropboxConfig.LEGACY_SENTINEL))
        var failClear = true
        val flaky = object : SecureTokenStore {
            override suspend fun read() = inner.read()
            override suspend fun write(t: TokenSet) = inner.write(t)
            override suspend fun clear() {
                if (failClear) { failClear = false; throw IllegalStateException("crash in clear") }
                inner.clear()
            }
        }
        val revoked = mutableListOf<TokenSet>()
        fun migration() = AppKeyMigration(
            prefs = h.prefs, tokens = flaky,
            exporter = object : com.example.data.sync.BackupExporter {
                override suspend fun export(sections: Set<com.example.data.sync.Section>?) =
                    SyncManagerBackupExporter { h.manager.exportSnapshotToFile() }.export(sections)
            },
            exportDir = h.exportDir, nowPhysical = { h.clock++ },
            onLegacyGrantCleared = { revoked += it }
        )

        val crashed = runCatching { migration().runIfNeeded() }.isFailure
        assertTrue(crashed)
        assertEquals("revoke handed off before the clear", 1, revoked.size)

        assertTrue(migration().runIfNeeded())
        assertNull(inner.read())
        assertEquals(DropboxConfigRule.TEST_APP_KEY, marker())
    }
}
