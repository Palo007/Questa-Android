package com.example.data.sync.auth

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE pair for one authorize/exchange round trip. [toString] never prints the verifier. */
data class Pkce(val verifier: String, val challenge: String) {
    override fun toString(): String = "Pkce(verifier=<redacted>, challenge=$challenge)"
}

/**
 * Tokens as stored in [SecureTokenStore]. [expiresAtMs] already has Opti's 60 s safety margin
 * applied (`Date.now() + max(0, expires_in - 60) * 1000`, sync.js :246 and :320).
 * [appKey] is the Dropbox app the grant belongs to: the app key in use when the token was
 * minted ([DropboxConfig.appKey], read at that moment), or [DropboxConfig.LEGACY_SENTINEL] for a
 * token moved by [TokenMigration]. [toString] never prints a token.
 */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val accountId: String?,
    val appKey: String = DropboxConfig.appKey()
) {
    /** A grant of the old Kotlin app key: it points at another app's `Apps/` folder (D16). */
    val isLegacyAppKey: Boolean get() = appKey != DropboxConfig.appKey()

    override fun toString(): String = "TokenSet(accessToken=<redacted>, refreshToken=<redacted>, expiresAtMs=$expiresAtMs, accountId=$accountId, appKey=$appKey)"
}

sealed interface RefreshResult {
    data class Ok(val tokens: TokenSet) : RefreshResult
    /** Dropbox `invalid_grant`: the refresh token was revoked or expired (Opti: enabled=false, "reconnect required"). */
    object InvalidGrant : RefreshResult
    data class Http(val status: Int, val summary: String?) : RefreshResult
    data class NetworkError(val message: String) : RefreshResult
}

/** Token exchange failed. The message never contains a token (only Dropbox's error text). */
class DropboxAuthException(message: String) : Exception(message)

/**
 * WP-8.1 -- PX-03 (app key), PX-04 (per-flavor redirect, encoded query), PX-08 (CSPRNG verifier).
 * Port of Opti `pkceVerifier`/`pkceChallenge` (sync.js :125-134), the authorize URL (:158-166),
 * the exchange (:219-249), `syncToken` refresh (:295-323) and `dbxRevokeGrant` (:726-756).
 * The auth endpoints are not Dropbox file calls and are not behind the D7 interlock: the account
 * id is only known after the exchange, and [revoke] must work for a refused account. The ONE
 * caller that must gate [refresh] is SyncManager: it runs `assertAllowed()` before a round's
 * pre-refresh, and DropboxService's 401 refresh runs only after its own `assertAllowed()`.
 */
class DropboxOAuth(
    private val rng: SecureRandom = SecureRandom(),
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() } // clock-ok:transport-timer
) {
    companion object {
        const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
        const val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
        const val REVOKE_URL = "https://api.dropboxapi.com/2/auth/token/revoke"
        private const val B64URL_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Opti `b64url`: standard base64 with + -> -, / -> _, padding removed. */
        fun base64Url(bytes: ByteArray): String {
            val sb = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i + 2 < bytes.size) {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                sb.append(B64URL_TABLE[(n ushr 18) and 63]).append(B64URL_TABLE[(n ushr 12) and 63])
                    .append(B64URL_TABLE[(n ushr 6) and 63]).append(B64URL_TABLE[n and 63])
                i += 3
            }
            val rest = bytes.size - i
            if (rest == 1) {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(B64URL_TABLE[(n ushr 18) and 63]).append(B64URL_TABLE[(n ushr 12) and 63])
            } else if (rest == 2) {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64URL_TABLE[(n ushr 18) and 63]).append(B64URL_TABLE[(n ushr 12) and 63])
                    .append(B64URL_TABLE[(n ushr 6) and 63])
            }
            return sb.toString()
        }

        /** Opti `pkceChallenge`: base64url(SHA-256(UTF-8 verifier)). */
        fun challengeFor(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))

        /** Opti `expires_in || 14400`, minus the 60 s margin, floored at 0. */
        internal fun expiresAt(nowMs: Long, expiresIn: Long?): Long {
            val secs = if (expiresIn == null || expiresIn == 0L) 14_400L else expiresIn
            return nowMs + maxOf(0L, secs - 60L) * 1000L
        }

        /** Dropbox sends `{"error":"invalid_grant",...}`; tolerate the `{".tag":...}` union shape too. */
        internal fun oauthErrorTag(text: String): String? = try {
            val obj = Json.parseToJsonElement(text) as? JsonObject
            when (val e = obj?.get("error")) {
                is JsonPrimitive -> e.contentOrNull
                is JsonObject -> (e[".tag"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        private fun oauthErrorText(text: String): String? = try {
            val obj = Json.parseToJsonElement(text) as? JsonObject
            (obj?.get("error_description") as? JsonPrimitive)?.contentOrNull ?: oauthErrorTag(text)
        } catch (e: Exception) {
            null
        }
    }

    /** Opti `pkceVerifier`: 64 CSPRNG bytes -> 86 base64url characters. */
    fun newPkce(): Pkce {
        val bytes = ByteArray(64)
        rng.nextBytes(bytes)
        val verifier = base64Url(bytes)
        return Pkce(verifier, challengeFor(verifier))
    }

    /** Same parameter set and order as Opti's URLSearchParams (sync.js :158-165); values are encoded. */
    fun authorizeUrl(p: Pkce): Uri =
        Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", DropboxConfig.appKey())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", p.challenge)
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("redirect_uri", DropboxConfig.REDIRECT_URI)
            .build()

    /** Throws [DropboxAuthException] on any failure (Opti: "connect failed: ..."). */
    suspend fun exchange(code: String, p: Pkce): TokenSet = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("code_verifier", p.verifier)
            .add("client_id", DropboxConfig.appKey())
            .add("redirect_uri", DropboxConfig.REDIRECT_URI)
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        val (status, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            throw DropboxAuthException("token exchange failed: network error")
        }
        if (status !in 200..299) {
            throw DropboxAuthException(oauthErrorText(text) ?: "token exchange failed: $status")
        }
        parseTokenSet(text, fallbackRefreshToken = null)
            ?: throw DropboxAuthException("token exchange failed: $status")
    }

    /** Opti `syncToken(true)` network part, with the grant's own [appKey]. Never throws. */
    suspend fun refresh(rt: String, appKey: String = DropboxConfig.appKey()): RefreshResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .add("client_id", appKey)
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        val (status, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            return@withContext RefreshResult.NetworkError(e.javaClass.simpleName)
        }
        val parsed = if (status in 200..299) parseTokenSet(text, fallbackRefreshToken = rt, appKey = appKey) else null
        when {
            parsed != null -> RefreshResult.Ok(parsed)
            oauthErrorTag(text) == "invalid_grant" -> RefreshResult.InvalidGrant
            else -> RefreshResult.Http(status, oauthErrorText(text) ?: "refresh failed: $status")
        }
    }

    /**
     * Opti `dbxRevokeGrant`: use the access token if still fresh, else mint one from the captured
     * refresh token first; best effort, never throws, false on any failure. The refresh sends the
     * grant's own app key (Opti `cfg.appKey || DBX_APP_KEY`, sync.js :740).
     */
    suspend fun revokeGrant(tokens: TokenSet?): Boolean {
        if (tokens == null) return false
        var at: String? = tokens.accessToken.takeIf { it.isNotEmpty() }
        val fresh = at != null && now() < tokens.expiresAtMs
        if (!fresh && tokens.refreshToken.isNotEmpty()) {
            val r = refresh(tokens.refreshToken, tokens.appKey)
            if (r is RefreshResult.Ok) at = r.tokens.accessToken
        }
        return at?.let { revoke(it) } ?: false
    }

    /** `/2/auth/token/revoke`. Best effort; never throws. */
    suspend fun revoke(at: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(REVOKE_URL)
            .header("Authorization", "Bearer $at")
            .post(FormBody.Builder().build())
            .build()
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** null when `access_token` is missing (Opti: `!data.access_token` is a failure). */
    private fun parseTokenSet(text: String, fallbackRefreshToken: String?, appKey: String = DropboxConfig.appKey()): TokenSet? {
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val accessToken = (json["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val refreshToken = (json["refresh_token"] as? JsonPrimitive)?.contentOrNull ?: fallbackRefreshToken ?: return null
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull
        // Dropbox returns `account_id` ("dbid:...") for user-linked apps. `uid` is a different
        // (numeric) id and is deliberately NOT used: it would never match the allowlist.
        val accountId = (json["account_id"] as? JsonPrimitive)?.contentOrNull
        return TokenSet(accessToken, refreshToken, expiresAt(now(), expiresIn), accountId, appKey)
    }
}
