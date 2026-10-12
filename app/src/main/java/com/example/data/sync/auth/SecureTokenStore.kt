package com.example.data.sync.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WP-8.1 -- PX-09. The only place Dropbox tokens are stored. Never touches `APP_KEY_OF_DATA`
 * (D16) or any other DataStore key.
 */
interface SecureTokenStore {
    suspend fun read(): TokenSet?
    suspend fun write(t: TokenSet)
    suspend fun clear()
}

/** Supplies the AES-256 key. Production: [AndroidKeystoreKeyProvider]. */
fun interface TokenKeyProvider {
    fun key(): SecretKey
}

/** Key generated inside, and never leaving, the Android Keystore (Bubblewrap DropboxAuth.kt pattern). */
object AndroidKeystoreKeyProvider : TokenKeyProvider {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "questa_dropbox_token_key"

    @Synchronized
    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * AES-256-GCM ciphertext (random 12-byte IV per write) in the private SharedPreferences file
 * [PREFS_NAME]. That file is excluded from Auto Backup and device transfer
 * (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`); the Keystore key is not
 * backed up either, so a restored blob could not be decrypted anyway ([read] returns null).
 */
class AndroidKeystoreTokenStore(
    context: Context,
    private val keyProvider: TokenKeyProvider = AndroidKeystoreKeyProvider
) : SecureTokenStore {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    companion object {
        const val PREFS_NAME = "questa_secure_tokens"
        private const val KEY_BLOB = "blob"
        private const val KEY_IV = "iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        internal fun encode(t: TokenSet): String = buildJsonObject {
            put("accessToken", t.accessToken)
            put("refreshToken", t.refreshToken)
            put("expiresAtMs", t.expiresAtMs)
            put("accountId", t.accountId)
            put("appKey", t.appKey)
        }.toString()

        internal fun decode(json: String): TokenSet? {
            val obj = Json.parseToJsonElement(json) as? JsonObject ?: return null
            val rt = (obj["refreshToken"] as? JsonPrimitive)?.contentOrNull ?: return null
            return TokenSet(
                accessToken = (obj["accessToken"] as? JsonPrimitive)?.contentOrNull ?: "",
                refreshToken = rt,
                expiresAtMs = obj["expiresAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                accountId = obj["accountId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
                // A blob without the field predates the fix; only TokenMigration (not yet called,
                // WP-8.21) ever writes a legacy-key token, and it now always sets the field.
                appKey = (obj["appKey"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: DropboxConfig.appKey()
            )
        }
    }

    override suspend fun read(): TokenSet? = withContext(Dispatchers.IO) {
        val blob = prefs.getString(KEY_BLOB, null) ?: return@withContext null
        val iv = prefs.getString(KEY_IV, null) ?: return@withContext null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.key(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            decode(String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (e: Exception) {
            // Key invalidated (Keystore reset, restored backup): same as "no token" -> reconnect.
            null
        }
    }

    /** Synchronous commit, then read-back check (Opti round 3 item 10: a lost token write is fatal). */
    override suspend fun write(t: TokenSet) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.key())
        val cipherText = cipher.doFinal(encode(t).toByteArray(Charsets.UTF_8))
        val ok = prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(cipherText, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        if (!ok) throw IllegalStateException("could not save the Dropbox tokens")
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_BLOB).remove(KEY_IV).commit()
        Unit
    }
}

/** JVM test double: no Keystore, no disk. */
class InMemorySecureTokenStore : SecureTokenStore {
    @Volatile private var stored: TokenSet? = null
    override suspend fun read(): TokenSet? = stored
    override suspend fun write(t: TokenSet) { stored = t }
    override suspend fun clear() { stored = null }
}
