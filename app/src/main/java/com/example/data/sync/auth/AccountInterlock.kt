package com.example.data.sync.auth

import com.example.BuildConfig
import com.example.data.sync.SyncDataStore

/** Account-linkage state (§4.3 `AUTH_STATE`). Never reflects a sync round's outcome. */
enum class AuthState { NOT_LINKED, LINKED, RECONNECT_REQUIRED }

sealed interface InterlockResult {
    object Allowed : InterlockResult
    data class Refused(val accountId: String?) : InterlockResult
}

/**
 * Thrown before any Dropbox request is sent when D7 refuses the account. [accountId] is null
 * when no account id is known at all. It is an ordinary checked-by-convention exception:
 * [com.example.data.sync.DropboxService] never catches it, so no call site can silently continue.
 */
class InterlockRefused(val accountId: String?) :
    Exception("Dropbox account is locked by the D7 interlock (REAL_ACCOUNT_UNLOCKED=false and the account is not in SCRATCH_ACCOUNT_IDS)")

/** What [com.example.data.sync.DropboxService] calls before every request. */
fun interface DropboxInterlock {
    suspend fun assertAllowed()
}

/**
 * D7 inputs. Defaults come from BuildConfig (`realAccountUnlocked` and `scratchAccountIds` in
 * local.properties). `internal var` only so JVM tests can set them; production never writes them.
 */
object InterlockPolicy {
    @Volatile internal var unlocked: Boolean = BuildConfig.REAL_ACCOUNT_UNLOCKED
    @Volatile internal var allowlist: Set<String> = parseIds(BuildConfig.SCRATCH_ACCOUNT_IDS)

    fun parseIds(raw: String): Set<String> =
        raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    internal fun resetToBuildConfig() {
        unlocked = BuildConfig.REAL_ACCOUNT_UNLOCKED
        allowlist = parseIds(BuildConfig.SCRATCH_ACCOUNT_IDS)
    }
}

/**
 * WP-8.1 -- D7 (with D30: the allowlist holds the real, isolated account id; the code is the
 * same). A request is allowed only when [unlocked] is true, or the linked account id is in
 * [allowlist]. Fail closed: an unknown (null or empty) account id is REFUSED unless unlocked,
 * so a token without a recorded account (for example one moved by [TokenMigration]) can never
 * reach Dropbox before a fresh connect records its id.
 *
 * [assertAllowed] is `suspend` (the card sketch shows a plain `fun`) because the linked id is
 * read from DataStore on every call, so a disconnect or account change takes effect at once.
 */
class AccountInterlock(
    private val accountIdSource: suspend () -> String?,
    private val unlocked: () -> Boolean = { InterlockPolicy.unlocked },
    private val allowlist: () -> Set<String> = { InterlockPolicy.allowlist }
) : DropboxInterlock {

    constructor(ds: SyncDataStore) : this({ ds.getAccountId() })

    private fun isAllowed(accountId: String?): Boolean {
        if (unlocked()) return true
        if (accountId.isNullOrEmpty()) return false
        return accountId in allowlist()
    }

    /** D7 "also at connect": run on the account id from the token response, BEFORE it is saved. */
    fun checkOnConnect(accountId: String?): InterlockResult =
        if (isAllowed(accountId)) InterlockResult.Allowed else InterlockResult.Refused(accountId)

    /** Called by every DropboxService request method (revoke is exempt and lives in DropboxOAuth). */
    override suspend fun assertAllowed() {
        val accountId = accountIdSource()
        if (!isAllowed(accountId)) throw InterlockRefused(accountId)
    }
}

/** DropboxService's default before SyncManager binds a real interlock: refuses everything. */
object UnboundInterlock : DropboxInterlock {
    override suspend fun assertAllowed() {
        throw InterlockRefused(null)
    }
}
