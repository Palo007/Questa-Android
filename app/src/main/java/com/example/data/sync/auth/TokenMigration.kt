package com.example.data.sync.auth

import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.flow.first

/**
 * WP-8.1 delivers this; only WP-8.21 `StartupMigrations` calls it (step 2b, after the D16
 * marker is decided). Moves pre-Stage-8 plaintext tokens (§4.3 rows DROPBOX_ACCESS_TOKEN :22,
 * DROPBOX_REFRESH_TOKEN :23, DROPBOX_EXPIRES_AT :24) into [SecureTokenStore], then deletes the
 * three keys. Idempotent: a second run finds no refresh token and only re-deletes the keys.
 *
 * The moved TokenSet has no account id (the legacy code never stored one), so the D7 interlock
 * refuses it until a fresh connect records the id (fail closed; see [AccountInterlock]). It is
 * marked with [DropboxConfig.LEGACY_SENTINEL] (it was minted by the old Kotlin app, whose key is unknown here), so
 * SyncManager reports it as RECONNECT_REQUIRED without a network call, and a disconnect revokes
 * it as a legacy grant.
 * DROPBOX_ENABLED and APP_KEY_OF_DATA are not touched.
 */
object TokenMigration {
    suspend fun moveFromDataStore(syncDataStore: SyncDataStore, tokenStore: SecureTokenStore) {
        val refreshToken = syncDataStore.dropboxRefreshToken.first()
        if (!refreshToken.isNullOrEmpty() && tokenStore.read() == null) {
            tokenStore.write(
                TokenSet(
                    accessToken = syncDataStore.dropboxAccessToken.first() ?: "",
                    refreshToken = refreshToken,
                    expiresAtMs = syncDataStore.dropboxExpiresAt.first(),
                    accountId = null,
                    appKey = DropboxConfig.LEGACY_SENTINEL
                )
            )
        }
        syncDataStore.clearLegacyTokenKeysOnly()
    }
}
