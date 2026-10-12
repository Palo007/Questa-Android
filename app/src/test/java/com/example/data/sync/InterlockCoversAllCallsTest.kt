package com.example.data.sync

import com.example.data.sync.auth.AccountInterlock
import com.example.data.sync.auth.InterlockPolicy
import com.example.data.sync.auth.InterlockRefused
import com.example.data.sync.auth.InterlockResult
import com.example.data.sync.auth.UnboundInterlock
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * WP-8.1 acceptance: "with a non-allowlisted id and UNLOCKED=false, every DropboxService method
 * (download, upload, list, move, delete, revoke excepted) throws InterlockRefused and the server
 * sees 0 requests." Plain JVM test: the interlock is constructed with explicit inputs, so the
 * result never depends on this machine's local.properties.
 */
class InterlockCoversAllCallsTest {

    private lateinit var fake: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient
    private var linked: String? = "dbid:not-on-the-list"
    private var unlocked = false
    private val allow = setOf("dbid:scratch")

    private val allMethods: List<Pair<String, suspend () -> Unit>> = listOf(
        "downloadState" to { DropboxService.downloadState("t") },
        "downloadFile" to { DropboxService.downloadFile("t", "/events/x.json") },
        "uploadState(add)" to { DropboxService.uploadState("t", "{}", null) },
        "uploadState(update)" to { DropboxService.uploadState("t", "{}", "rev1") },
        "uploadOverwrite" to { DropboxService.uploadOverwrite("t", "/events/x.json", "[]") },
        "listFolder" to { DropboxService.listFolder("t", "/events") },
        "listFolderContinue" to { DropboxService.listFolderContinue("t", "c") },
        "move" to { DropboxService.move("t", "/inbox/a.json", "/inbox-claimed/d/a.json") },
        "delete" to { DropboxService.delete("t", "/events/x.json") },
        // CR-KT-015 attachment blobs.
        "uploadBlob" to { DropboxService.uploadBlob("t", "/attachments/a.jpg", byteArrayOf(1)) },
        "downloadBlob" to { DropboxService.downloadBlob("t", "/attachments/a.jpg") }
    )

    @Before fun setUp() {
        fake = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fake.asClient()
        DropboxService.bindAuth(AccountInterlock({ linked }, { unlocked }, { allow }), null)
    }

    @After fun tearDown() {
        DropboxService.client = originalClient
        DropboxService.bindAuth(UnboundInterlock, null)
    }

    private fun assertAllRefused(expectedId: String?) = runBlocking {
        for ((name, call) in allMethods) {
            try {
                call()
                fail("$name was not refused")
            } catch (e: InterlockRefused) {
                assertEquals(name, expectedId, e.accountId)
            }
        }
        assertEquals("server must see 0 requests", 0, fake.requests.size)
    }

    @Test fun nonAllowlistedAccount_everyMethodRefused_zeroRequests() = assertAllRefused("dbid:not-on-the-list")

    @Test fun unknownAccount_isRefused_failClosed() {
        linked = null
        assertAllRefused(null)
    }

    @Test fun emptyAccountId_isRefused() {
        linked = ""
        assertAllRefused("")
    }

    @Test fun unboundInterlock_refusesEverything() {
        DropboxService.interlock = UnboundInterlock
        assertAllRefused(null)
    }

    @Test fun refusalHappensBeforeThe401Refresher() = runBlocking {
        var refreshed = 0
        DropboxService.tokenRefresher = { refreshed++; "fresh" }
        try { DropboxService.downloadState("t"); fail() } catch (e: InterlockRefused) { }
        assertEquals(0, refreshed)
        assertEquals(0, fake.requests.size)
    }

    @Test fun allowlistedAccount_everyMethodReachesTheServer() = runBlocking {
        linked = "dbid:scratch"
        for ((_, call) in allMethods) call()
        assertEquals(allMethods.size, fake.requests.size)
    }

    @Test fun unlocked_allowsAnyAccount() = runBlocking {
        unlocked = true
        linked = null
        for ((_, call) in allMethods) call()
        assertEquals(allMethods.size, fake.requests.size)
    }

    @Test fun checkOnConnect() {
        val i = AccountInterlock({ null }, { false }, { allow })
        assertEquals(InterlockResult.Allowed, i.checkOnConnect("dbid:scratch"))
        assertEquals(InterlockResult.Refused("dbid:other"), i.checkOnConnect("dbid:other"))
        assertEquals(InterlockResult.Refused(null), i.checkOnConnect(null))
        assertEquals(InterlockResult.Allowed, AccountInterlock({ null }, { true }, { emptySet() }).checkOnConnect("dbid:other"))
    }

    @Test fun policyParsesLocalPropertiesList() {
        assertEquals(setOf("dbid:a", "dbid:b", "dbid:c"), InterlockPolicy.parseIds(" dbid:a, dbid:b;dbid:c "))
        assertTrue(InterlockPolicy.parseIds("").isEmpty())
    }

    @Test fun buildConfigDefault_isLocked() {
        // D7: REAL_ACCOUNT_UNLOCKED stays false until WP-8.19.
        //
        // Assert the DEFAULT, not the value in this build: the flag is read from local.properties
        // (`localProp("realAccountUnlocked") ?: "false"`), so asserting on BuildConfig here would
        // fail on any machine that has legitimately unlocked real-account access for development --
        // which is exactly what happened on the maintainer's own checkout. What this repository
        // can guarantee is that a clone with no local.properties ships locked.
        val gradle = com.example.buildcheck.SourceFiles.read("build.gradle.kts")
        val decl = Regex("""buildConfigField\("boolean",\s*"REAL_ACCOUNT_UNLOCKED".*""").find(gradle)
        assertTrue(
            "REAL_ACCOUNT_UNLOCKED buildConfigField declaration not found in app/build.gradle.kts",
            decl != null)
        assertTrue(
            "the committed default for REAL_ACCOUNT_UNLOCKED must be false (D7), but the " +
                "declaration reads: " + decl!!.value.trim(),
            decl.value.contains("""?: "false"""))
    }
}
