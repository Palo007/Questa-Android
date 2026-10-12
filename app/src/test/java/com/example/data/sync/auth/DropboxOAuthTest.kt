package com.example.data.sync.auth

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom

/**
 * WP-8.1 -- PX-03 (app key), PX-04 (redirect + encoding), PX-07 (invalid_grant), PX-08 (CSPRNG),
 * G7 transport strings. Fake OkHttp interceptors only; no socket is ever opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropboxOAuthTest {

    @get:Rule val config = DropboxConfigRule()

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/opti/manifest.json").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root not found")
    }

    private fun g7(key: String): String {
        val obj = Json.parseToJsonElement(File(repoRoot(), "fixtures/opti/G7/transport_strings.json").readText()) as JsonObject
        return (obj[key] as JsonPrimitive).content
    }

    private class Recorder(val code: Int, val body: String) {
        val seen = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private fun form(r: Request): Map<String, String> {
        val b = r.body as FormBody
        return (0 until b.size).associate { b.name(it) to b.value(it) }
    }

    // ---- constants and G7 ----

    @Test fun `authorizeUrl uses the runtime app key`() {
        DropboxConfig.setCacheForTests("runtime-key-123")
        val oauth = DropboxOAuth()
        val url = oauth.authorizeUrl(oauth.newPkce())
        assertTrue(url.toString().contains("client_id=runtime-key-123"))
    }

    @Test fun `a grant under another key is legacy, the current key is not`() {
        assertTrue(TokenSet("a", "r", 0L, null, appKey = "other").isLegacyAppKey)
        assertFalse(TokenSet("a", "r", 0L, null, appKey = DropboxConfigRule.TEST_APP_KEY).isLegacyAppKey)
        assertTrue(TokenSet("a", "r", 0L, null, appKey = DropboxConfig.LEGACY_SENTINEL).isLegacyAppKey)
    }

    @Test fun appKeys() {
        // The runtime key must never equal the legacy marker, or an upgrade could not be detected.
        assertNotEquals(
            "the runtime key and LEGACY_SENTINEL must differ, or AppKeyMigration can never detect an upgrade",
            DropboxConfig.appKey(), DropboxConfig.LEGACY_SENTINEL)
    }

    @Test fun redirectUri_isTheDebugScheme_inUnitTests() {
        // Unit tests run the debug variant (applicationIdSuffix .dev): questa-dev://oauth.
        assertEquals("questa-dev://oauth", DropboxConfig.REDIRECT_URI)
    }

    @Test fun g7_pkceChallenge() {
        assertEquals(g7("pkce_challenge"), DropboxOAuth.challengeFor(g7("pkce_verifier")))
    }

    @Test fun g7_b64url() {
        assertEquals(g7("b64url_ascii"), DropboxOAuth.base64Url("hello/world+padding?=".toByteArray(Charsets.UTF_8)))
        val bin = byteArrayOf(0, 255.toByte(), 16, 32, 254.toByte(), 1)
        assertEquals(g7("b64url_binary"), DropboxOAuth.base64Url(bin))
    }

    @Test fun b64url_matchesJavaUrlEncoder_forAllLengths() {
        val rng = java.util.Random(7)
        for (n in 0..70) {
            val bytes = ByteArray(n).also { rng.nextBytes(it) }
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), DropboxOAuth.base64Url(bytes))
        }
    }

    // ---- PKCE (PX-08) ----

    @Test fun verifier_is86UrlSafeChars_like64RandomBytes() {
        val p = DropboxOAuth().newPkce()
        assertEquals(86, p.verifier.length)
        assertTrue(p.verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertEquals(DropboxOAuth.challengeFor(p.verifier), p.challenge)
    }

    @Test fun verifier_usesTheInjectedSecureRandom() {
        var calls = 0
        val rng = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { calls++; bytes.fill(1) }
        }
        val p = DropboxOAuth(rng = rng).newPkce()
        assertEquals(1, calls)
        assertEquals(DropboxOAuth.base64Url(ByteArray(64) { 1 }), p.verifier)
        assertNotEquals(DropboxOAuth().newPkce().verifier, DropboxOAuth().newPkce().verifier)
    }

    // ---- authorize URL (PX-04, SY-02) ----

    @Test fun authorizeUrl_hasExactlyOptiParamSet_encoded() {
        val p = Pkce("v", "ch+/=")
        val uri = DropboxOAuth().authorizeUrl(p)
        assertEquals("https", uri.scheme)
        assertEquals("www.dropbox.com", uri.host)
        assertEquals("/oauth2/authorize", uri.path)
        assertEquals(
            setOf("client_id", "response_type", "code_challenge_method", "code_challenge", "token_access_type", "redirect_uri"),
            uri.queryParameterNames
        )
        assertEquals(DropboxConfigRule.TEST_APP_KEY, uri.getQueryParameter("client_id"))
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("ch+/=", uri.getQueryParameter("code_challenge"))
        assertEquals("offline", uri.getQueryParameter("token_access_type"))
        assertEquals(DropboxConfig.REDIRECT_URI, uri.getQueryParameter("redirect_uri"))
        // The redirect is percent-encoded in the raw query (the old string concat was not).
        assertTrue(uri.encodedQuery!!.contains("redirect_uri=questa-dev%3A%2F%2Foauth"))
    }

    // ---- exchange ----

    @Test fun exchange_sendsOptiForm_andParsesTokens() = runBlocking {
        val rec = Recorder(200, """{"access_token":"at1","refresh_token":"rt1","expires_in":14400,"account_id":"dbid:abc","uid":"123"}""")
        val oauth = DropboxOAuth(http = rec.client, now = { 1_000_000L })
        val t = oauth.exchange("code123", Pkce("verif", "ch"))
        assertEquals("at1", t.accessToken)
        assertEquals("rt1", t.refreshToken)
        assertEquals("dbid:abc", t.accountId)
        assertEquals(1_000_000L + (14_400L - 60L) * 1000L, t.expiresAtMs)
        val r = rec.seen.single()
        assertEquals(DropboxOAuth.TOKEN_URL, r.url.toString())
        assertEquals(
            mapOf("code" to "code123", "grant_type" to "authorization_code", "code_verifier" to "verif",
                "client_id" to DropboxConfigRule.TEST_APP_KEY, "redirect_uri" to DropboxConfig.REDIRECT_URI),
            form(r)
        )
    }

    @Test fun exchange_noAccountId_givesNull_uidIsNotUsed() = runBlocking {
        val rec = Recorder(200, """{"access_token":"at1","refresh_token":"rt1","uid":"123"}""")
        val t = DropboxOAuth(http = rec.client, now = { 0L }).exchange("c", Pkce("v", "c"))
        assertEquals(null, t.accountId)
        assertEquals((14_400L - 60L) * 1000L, t.expiresAtMs)
    }

    @Test fun exchange_failure_throws_withoutLeakingTheRequest() = runBlocking {
        val rec = Recorder(400, """{"error":"invalid_grant","error_description":"code doesn't exist or has expired"}""")
        try {
            DropboxOAuth(http = rec.client).exchange("secret-code", Pkce("secret-verifier", "c"))
            fail("expected DropboxAuthException")
        } catch (e: DropboxAuthException) {
            assertEquals("code doesn't exist or has expired", e.message)
            assertFalse(e.message!!.contains("secret"))
        }
    }

    @Test fun exchange_200WithoutAccessToken_throws() = runBlocking {
        val rec = Recorder(200, """{"refresh_token":"rt"}""")
        try {
            DropboxOAuth(http = rec.client).exchange("c", Pkce("v", "c"))
            fail("expected DropboxAuthException")
        } catch (e: DropboxAuthException) { }
    }

    // ---- refresh (PX-07) ----

    @Test fun refresh_invalidGrant_realDropboxShape() = runBlocking {
        val rec = Recorder(400, """{"error":"invalid_grant","error_description":"refresh token is invalid or revoked"}""")
        assertEquals(RefreshResult.InvalidGrant, DropboxOAuth(http = rec.client).refresh("rt"))
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "rt", "client_id" to DropboxConfigRule.TEST_APP_KEY), form(rec.seen.single()))
    }

    @Test fun refresh_invalidGrant_unionShapeAlsoAccepted() = runBlocking {
        val rec = Recorder(400, """{"error":{".tag":"invalid_grant"}}""")
        assertEquals(RefreshResult.InvalidGrant, DropboxOAuth(http = rec.client).refresh("rt"))
    }

    @Test fun refresh_otherError_isHttp() = runBlocking {
        val rec = Recorder(500, "oops")
        val r = DropboxOAuth(http = rec.client).refresh("rt")
        assertTrue(r is RefreshResult.Http && r.status == 500)
    }

    @Test fun refresh_ok_keepsRefreshTokenWhenOmitted() = runBlocking {
        val rec = Recorder(200, """{"access_token":"at2","expires_in":100}""")
        val r = DropboxOAuth(http = rec.client, now = { 10L }).refresh("rt-original") as RefreshResult.Ok
        assertEquals("at2", r.tokens.accessToken)
        assertEquals("rt-original", r.tokens.refreshToken)
        assertEquals(10L + 40_000L, r.tokens.expiresAtMs)
    }

    @Test fun refresh_networkError_neverThrows() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        assertTrue(DropboxOAuth(http = client).refresh("rt") is RefreshResult.NetworkError)
    }

    @Test fun expiresAt_marginAndDefaults() {
        assertEquals(1_000L + 14_340_000L, DropboxOAuth.expiresAt(1_000L, null))
        assertEquals(1_000L + 14_340_000L, DropboxOAuth.expiresAt(1_000L, 0L))
        assertEquals(1_000L, DropboxOAuth.expiresAt(1_000L, 30L))
    }

    // ---- revoke (PX-10) ----

    @Test fun revoke_sendsBearerToRevokeEndpoint() = runBlocking {
        val rec = Recorder(200, "null")
        assertTrue(DropboxOAuth(http = rec.client).revoke("at-x"))
        val r = rec.seen.single()
        assertEquals(DropboxOAuth.REVOKE_URL, r.url.toString())
        assertEquals("Bearer at-x", r.header("Authorization"))
    }

    @Test fun revoke_failure_isFalse_neverThrows() = runBlocking {
        assertFalse(DropboxOAuth(http = Recorder(500, "").client).revoke("at"))
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        assertFalse(DropboxOAuth(http = client).revoke("at"))
    }

    @Test fun revokeGrant_expiredAccess_mintsFromRefreshFirst() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request())
            val body = if (chain.request().url.toString() == DropboxOAuth.TOKEN_URL) """{"access_token":"minted","expires_in":14400}""" else "null"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val oauth = DropboxOAuth(http = client, now = { 5_000L })
        assertTrue(oauth.revokeGrant(TokenSet("stale", "rt", expiresAtMs = 4_000L, accountId = "dbid:a")))
        assertEquals(listOf(DropboxOAuth.TOKEN_URL, DropboxOAuth.REVOKE_URL), seen.map { it.url.toString() })
        assertEquals("Bearer minted", seen[1].header("Authorization"))
    }

    @Test fun revokeGrant_freshAccess_revokesDirectly() = runBlocking {
        val rec = Recorder(200, "null")
        assertTrue(DropboxOAuth(http = rec.client, now = { 1L }).revokeGrant(TokenSet("fresh", "rt", 10_000L, null)))
        assertEquals(listOf(DropboxOAuth.REVOKE_URL), rec.seen.map { it.url.toString() })
        assertFalse(DropboxOAuth(http = rec.client).revokeGrant(null))
    }

    @Test fun tokenSetToString_redactsTokens() {
        val s = TokenSet("sl.secretAccess", "secretRefresh", 1L, "dbid:a").toString()
        assertFalse(s.contains("secret"))
    }
}
