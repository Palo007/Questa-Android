package com.example.data.sync

import android.content.Context
import android.net.Uri
import com.example.data.RpgRepository
import com.example.data.clock.HlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.entities.*
import com.example.data.sync.SyncResult as RoundResult
import com.example.data.sync.auth.*
import com.example.data.sync.merge.ConflictEvent
import com.example.data.sync.merge.ConflictSink
import com.example.data.sync.merge.ConflictThrottle
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.merge.OptiMergeTypeError
import com.example.data.sync.merge.OptiSnapshotMerger
import com.example.data.sync.merge.SnapshotMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class SyncManager(
    private val context: Context,
    private val repository: RpgRepository,
    private val syncDataStore: SyncDataStore,
    // WP-5.1 (spec §7 D10) -- invoked at pushStateWithConflictRetry's exhaustion branch, BEFORE
    // returning SyncResult.Error, so a caller that has a scheduler (SyncScheduler) can react to the
    // exhaustion by arming its own conflict-backoff retry (mirrors PWA's
    // `setTimeout(syncNow, SYNC_CONFLICT_BACKOFF_MS)`, sync.js:1162) instead of treating the
    // returned Error as terminal. `syncNow()` itself still returns the Error either way -- see
    // pushStateWithConflictRetry's doc for why this callback does not change that return value.
    private val onConflictExhausted: (() -> Unit)? = null,
    // WP-8.1 (PX-09): the only token home. Tests pass an InMemorySecureTokenStore.
    internal val tokenStore: SecureTokenStore = AndroidKeystoreTokenStore(context),
    private val dropboxOAuth: DropboxOAuth = DropboxOAuth(),
    // WP-8.5: the merge core (plan §5 WP-8.5 "Wiring": SnapshotMerger -> OptiSnapshotMerger).
    private val merger: SnapshotMerger = OptiSnapshotMerger(),
    // WP-8.5 (D18): the physical clock for the backup file stamp and the auto-export check.
    private val physicalClock: () -> Long = SyncClocks.backupLocal,
    // ---- WP-8.6 seams (all optional; production uses the defaults) ----
    /** The sync base; default: the repository's database, else the process database. */
    private val baseStore: BaseStore? = null,
    /** The HLC the round ratchets; default: the repository's (the process HLC). */
    private val hlcClock: HlcClock? = null,
    /** Replaces the built-in EVENTS / BACKUPS steps (8.7 / 8.8 / 8.9 plug in here). */
    private val roundSteps: List<SyncRoundStep>? = null,
    /** The transient-retry wait (RetryPolicy ladder / Retry-After). */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    /** merge()'s physical now (MergeContext.nowPhysical). */
    private val mergeClock: () -> Long = SyncClocks.mergeNow,
    /** `wrap().savedAt` and `lastSyncAt`. */
    private val wrapperClock: () -> Long = SyncClocks.wrapperSavedAt,
    /** Token expiry and the event-pull throttle. */
    private val transportClock: () -> Long = SyncClocks.transport,
    /** The physical now handed to the tap queue drain. */
    private val tapClock: () -> Long = SyncClocks.tap,
    /**
     * WP-8.I (W4, 8.9 patch 7): the live backup source for [exportSnapshotToFile] (the pause from
     * the live `sync_prefs` row). Production always passes it ([AppSyncWiring.liveBackup]); null
     * keeps the legacy `exportBackup` body for older tests only.
     */
    private val backupSource: LiveBackupSource? = null,
    /** WP-8.I (W4, 8.9 patch 6): [disconnect] clears `pending_force_push` (sync.js:777). */
    private val clearPendingForcePush: (suspend () -> Unit)? = null,
    /**
     * CR-KT-015: attachment blobs. uploadPending runs BEFORE the state.json push; onRoundFinished
     * after a settled round. Any failure is swallowed: it never blocks the state push.
     */
    private val blobSync: com.example.data.attachments.AttachmentBlobSync? = null,
) : SyncEngineInternals {

    // ---- WP-8.1 auth block (D7, PX-03/04/07/08/09/10) ------------------------------------------
    // App key comes from DropboxConfig.appKey(), read at runtime (never committed),
    // per-flavor redirect (PX-04), CSPRNG
    // PKCE (PX-08), tokens only in SecureTokenStore (PX-09), refresh-on-401 and invalid_grant ->
    // RECONNECT_REQUIRED (PX-07), revoke on disconnect with Opti's keep set (PX-10).
    private val accountInterlock = AccountInterlock(syncDataStore)

    init {
        // D7: the one production binding of DropboxService's gate and its 401 refresher.
        DropboxService.bindAuth(accountInterlock) { forceRefreshAccessToken() }
    }

    val authState: kotlinx.coroutines.flow.Flow<AuthState> get() = syncDataStore.authState

    suspend fun getAuthorizationUri(): String {
        val pkce = dropboxOAuth.newPkce()
        syncDataStore.saveOAuthVerifier(pkce.verifier)
        return dropboxOAuth.authorizeUrl(pkce).toString()
    }

    /**
     * KT-61 (fix plan P9): [CONNECT_REFUSED_MESSAGE] when the last [handleRedirectUri] was refused
     * by the interlock (or had no account id), else null. MainActivity shows it as a toast.
     */
    @Volatile var lastConnectRefusal: String? = null
        private set

    /**
     * [connectFailedMessage] when the last [handleRedirectUri] got a code but could not finish
     * (token exchange or token save failed), else null. A cancelled login leaves it null.
     * MainActivity shows it as a toast, so a failed connect does not look like nothing happened.
     */
    @Volatile var lastConnectFailure: String? = null
        private set

    /**
     * OAuth return (Opti `syncHandleRedirect`). Returns true only when the tokens were saved and
     * the account is linked. Order: exchange -> D7 check on the returned account id -> save
     * tokens (read back) -> mark LINKED. A refused account never gets saved; its fresh grant is
     * revoked (best effort). The verifier is cleared on every OAuth return, success or not.
     * A successful connect also deletes the legacy plaintext token keys, so the old grant can
     * never be picked up in place of the new one.
     *
     * Network calls here are allowed by design, not gated by [AccountInterlock.assertAllowed]:
     * the exchange (the account id is only known from its answer, and [AccountInterlock.checkOnConnect]
     * runs on it before anything is saved) and the revoke of a refused grant. No
     * `get_current_account` call exists (Opti has none either).
     */
    suspend fun handleRedirectUri(uriString: String): Boolean {
        android.util.Log.i(AUTH_TAG, "connect: redirect received")
        lastConnectRefusal = null
        lastConnectFailure = null
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            android.util.Log.w(AUTH_TAG, "connect: redirect not parsable")
            return false
        }
        val expected = Uri.parse(DropboxConfig.REDIRECT_URI)
        if (uri.scheme != expected.scheme || uri.host != expected.host) {
            android.util.Log.w(AUTH_TAG, "connect: wrong redirect ${uri.scheme}://${uri.host}")
            return false
        }
        val code = uri.getQueryParameter("code")
        val verifier = syncDataStore.getOAuthVerifier()?.takeIf { it.isNotEmpty() }
        if (code.isNullOrEmpty() || verifier == null) {
            android.util.Log.w(AUTH_TAG, "connect: code=${!code.isNullOrEmpty()} verifier=${verifier != null} error=${uri.getQueryParameter("error")}")
            // Opti: only a real OAuth return (?code= or ?error=) clears the pending verifier.
            if (!code.isNullOrEmpty() || uri.getQueryParameter("error") != null) syncDataStore.clearOAuthVerifier()
            return false
        }
        val tokens = try {
            dropboxOAuth.exchange(code, Pkce(verifier, ""))
        } catch (e: DropboxAuthException) {
            android.util.Log.w(AUTH_TAG, "connect: exchange failed: ${e.message}")
            lastConnectFailure = connectFailedMessage(e.message)
            syncDataStore.clearOAuthVerifier()
            return false
        }
        val accountId = tokens.accountId
        if (accountId.isNullOrEmpty() || accountInterlock.checkOnConnect(accountId) !is InterlockResult.Allowed) {
            android.util.Log.w(AUTH_TAG, "connect: refused by interlock, accountId present=${!accountId.isNullOrEmpty()}")
            syncDataStore.clearOAuthVerifier()
            dropboxOAuth.revoke(tokens.accessToken)
            // KT-61 (fix plan P9): say why, so a refusal does not look like a cancelled login.
            lastConnectRefusal = CONNECT_REFUSED_MESSAGE
            syncDataStore.saveSyncStatus(CONNECT_REFUSED_MESSAGE)
            return false
        }
        return try {
            tokenStore.write(tokens)
            if (tokenStore.read()?.refreshToken != tokens.refreshToken) {
                android.util.Log.w(AUTH_TAG, "connect: token read-back mismatch")
                lastConnectFailure = connectFailedMessage("could not save the login")
                syncDataStore.clearOAuthVerifier()
                return false
            }
            syncDataStore.markLinked(accountId)
            syncDataStore.clearLegacyTokenKeysOnly()
            // KT-61: a stale connect refusal must not stay on the status line of a linked account.
            if (syncDataStore.lastSyncError.first() == CONNECT_REFUSED_MESSAGE) syncDataStore.saveSyncStatus(null)
            android.util.Log.i(AUTH_TAG, "connect: linked")
            true
        } catch (e: Exception) {
            android.util.Log.w(AUTH_TAG, "connect: save failed: ${e.javaClass.simpleName}: ${e.message}")
            lastConnectFailure = connectFailedMessage("could not save the login")
            syncDataStore.clearOAuthVerifier()
            false
        }
    }

    /**
     * Opti `syncDisconnect`: local state is cleaned first (the user is disconnected whether or
     * not Dropbox is reachable), then the captured grant is revoked, best effort. Returns the
     * revoke result for callers and tests. Keeps the base and APP_KEY_OF_DATA (D8, D16).
     * Allowed by design without the D7 gate (Opti parity): a refused account must still be able
     * to revoke its own grant. The revoke (and its refresh) use the grant's own app key.
     */
    suspend fun disconnect(): Boolean {
        // WP-8.21: before TokenMigration has run, the grant is still the legacy plaintext one;
        // capture it too, so it is revoked and not only deleted locally.
        val captured = tokenStore.read() ?: legacyPlaintextTokens()
        tokenStore.clear()
        syncDataStore.clearForDisconnect()
        clearPendingForcePush?.invoke()
        return dropboxOAuth.revokeGrant(captured)
    }

    /**
     * Opti `syncToken()`: the stored access token while it is fresh, else a refresh. null means
     * "not usable now" (not linked, disabled, reconnect required, or refresh failed). The caller
     * ([syncNowUnchecked]) has already passed the D7 interlock, so the refresh is gated too.
     */
    private suspend fun getActiveAccessToken(): String? {
        if (!syncDataStore.isDropboxEnabled.first()) return null
        val current = usableTokens() ?: return null
        if (current.accessToken.isNotEmpty() && transportClock() < current.expiresAtMs) return current.accessToken
        return refreshFrom(current)
    }

    /**
     * WP-8.I (W4, orchestrator-approved): the token for work outside a round (8.9's forced event
     * pull/push, the reset purge). D7 first, then the SAME read/refresh path as [syncNowUnchecked]
     * ([getActiveAccessToken] -> [usableTokens]: account match, legacy fallback, legacy app key ->
     * RECONNECT_REQUIRED with no network call). Throws the interlock refusal. Never log the result.
     */
    internal suspend fun activeAccessTokenChecked(): String? {
        accountInterlock.assertAllowed()
        return getActiveAccessToken()
    }

    /** DropboxService's 401 hook (Opti `syncToken(true)`); DropboxService ran `assertAllowed()` first. */
    private suspend fun forceRefreshAccessToken(): String? {
        if (!syncDataStore.isDropboxEnabled.first()) return null
        return refreshFrom(usableTokens() ?: return null)
    }

    /**
     * [currentTokens], minus the grants this app must never send:
     * - a token of another account than the linked ACCOUNT_ID (null, no network);
     * - a token minted under [DropboxConfig.LEGACY_SENTINEL] (a grant from before the app key was
     *   recorded): it points at the old app's `Apps/` folder and its refresh would need the old key, so
     *   it is RECONNECT_REQUIRED at once, without a network call.
     */
    private suspend fun usableTokens(): TokenSet? {
        val current = currentTokens() ?: return null
        val linked = syncDataStore.getAccountId()
        if (!linked.isNullOrEmpty() && !current.accountId.isNullOrEmpty() && current.accountId != linked) return null
        if (current.isLegacyAppKey) {
            syncDataStore.markReconnectRequired()
            return null
        }
        return current
    }

    /**
     * The token to use. [tokenStore] is the only source while ACCOUNT_ID is set: its read()
     * returns null on a decrypt error, and the old plaintext DataStore tokens (maybe of another
     * account) must never stand in for it.
     *
     * With no ACCOUNT_ID, the legacy plaintext tokens are a READ-ONLY fallback for installs where
     * WP-8.21 has not yet run TokenMigration. They were minted under the old app key, so
     * [usableTokens] turns them into RECONNECT_REQUIRED; with no account id the D7 interlock
     * refuses them first unless unlocked. A refresh is always written to [tokenStore].
     */
    private suspend fun currentTokens(): TokenSet? {
        tokenStore.read()?.let { return it }
        if (!syncDataStore.getAccountId().isNullOrEmpty()) return null
        return legacyPlaintextTokens()
    }

    /** The pre-Stage-8 plaintext grant (old app key), or null. */
    private suspend fun legacyPlaintextTokens(): TokenSet? {
        val rt = syncDataStore.dropboxRefreshToken.first()?.takeIf { it.isNotEmpty() } ?: return null
        return TokenSet(
            accessToken = syncDataStore.dropboxAccessToken.first() ?: "",
            refreshToken = rt,
            expiresAtMs = syncDataStore.dropboxExpiresAt.first(),
            accountId = null,
            appKey = DropboxConfig.LEGACY_SENTINEL
        )
    }

    private suspend fun refreshFrom(current: TokenSet): String? =
        when (val result = dropboxOAuth.refresh(current.refreshToken, current.appKey)) {
            is RefreshResult.Ok -> {
                // The refresh response carries no account id; keep the linked one.
                tokenStore.write(result.tokens.copy(accountId = result.tokens.accountId ?: current.accountId))
                result.tokens.accessToken
            }
            RefreshResult.InvalidGrant -> {
                syncDataStore.markReconnectRequired()
                null
            }
            else -> null
        }

    companion object {
        /**
         * D7 (spec §7 D7; PWA `legacy_pwa/sync.js:22`, `SYNC_CONFLICT_RETRY_LIMIT = 3`) -- max
         * number of RETRY attempts after an initial conflicted `/state.json` upload (i.e. up to
         * `1 + SYNC_CONFLICT_RETRY_LIMIT` total upload tries). Ported verbatim from PWA's
         * `_pushWithConflictRetry` recursion guard (`attempt < SYNC_CONFLICT_RETRY_LIMIT`,
         * sync.js:1142 and :1214 (the `syncForcePush` sibling)): `attempt` is 0-indexed and names
         * the try that just failed, so attempts 0, 1, 2 may each retry (producing retry attempts
         * 1, 2, 3) and attempt 3 failing exhausts the budget -- 4 total upload tries, 3 of which
         * are conflict-triggered retries. See `pushStateWithConflictRetry`'s doc for the full loop.
         */
        private const val SYNC_CONFLICT_RETRY_LIMIT = 3

        /** WP-5.2 (spec §7 D9; PWA `legacy_pwa/sync.js:1289`, `EXPORT_BACKUP_PATH`). */
        private const val EXPORT_BACKUP_PATH = "/export-backup.json"

        /** WP-8.6: the legacy [syncNow] text for [RoundResult.NeedsFirstConnectChoice]. */
        const val FIRST_CONNECT_MESSAGE = "Dropbox already has data: choose Pull or Push first"

        /** sync.js:2455-2458. */
        const val MERGE_INVALID_MESSAGE = "merge produced invalid state; sync aborted"

        /** sync.js:2475, :2515. */
        const val BASE_WRITE_FAILED_MESSAGE = "base snapshot write failed — sync degraded"

        /** sync.js:2544. */
        const val CONFLICT_MESSAGE = "sync conflict — retry later"

        /** WP-8.1 (D7): the round's error text when the interlock refuses the linked account. */
        const val INTERLOCK_REFUSED_MESSAGE = "Dropbox account is locked (real-account interlock)"

        /** KT-61 (fix plan P9): shown when the interlock refuses an account at connect. */
        const val CONNECT_REFUSED_MESSAGE = "This Dropbox account is locked. It was not connected."
        /** Shown when a connect with a code could not finish; [reason] is never a token or code. */
        fun connectFailedMessage(reason: String?): String =
            "Dropbox connect failed (${reason ?: "unknown error"}). Try again."

        /** Logcat tag for the connect flow (no tokens, codes or ids are logged). */
        const val AUTH_TAG = "QuestaAuth"

        /** Opti's HttpError text shape: "<what>: <status> <detail>" (status 0 = network error). */
        internal fun httpErrorText(what: String, status: Int, summary: String?): String =
            "$what: $status" + if (!summary.isNullOrEmpty()) " $summary" else ""
    }

    // ---- WP-8.6: the round (PARITY_FIX_PLAN §5 WP-8.6 "Round order"; Opti sync.js:2349-2556) ----

    private val localState = LocalState(repository, syncDataStore)

    /** The base store: the repository's (its database) or the process database's. */
    private val bases: BaseStore by lazy {
        baseStore ?: repository.baseStore ?: RoomBaseStore(AppDatabase.getDatabase(context).syncBaseDao())
    }

    private val hlc: HlcClock get() = hlcClock ?: repository.hlcClock

    /** WP-8.6: taps drained at the end of every settled round (bound by 8.I, see WP-8.6 notes). */
    @Volatile var tapQueue: com.example.data.LocalTapQueue? = null

    /**
     * Opti `syncSubset()` (sync.js:806-827) from Room: the 12 keys in Opti order. [deletions] is
     * ignored (kept for older callers): the tombstones are read from Room.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildLocalSnapshot(deletions: List<Pair<String, Long>> = emptyList()): JsonObject =
        repository.inTransaction { localState.subset(readBase()?.second) }

    /**
     * WP-8.6: one round through the process-wide [SyncLock] (StartupBarrier first). null = the
     * lock was held and [trigger] skips ([SyncTrigger.WORKER] only). The lock's `finally` settles
     * the BootGate (§5.0 B4, D28). Pending launcher taps are drained afterwards, outside the lock.
     */
    suspend fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL): RoundResult? {
        val r = SyncLock.singleFlight(trigger) { runRound() }
        tapQueue?.let { q -> try { q.drain(tapClock()) } catch (e: CancellationException) { throw e } catch (e: Exception) { } }
        return r
    }

    /**
     * Pre-8.6 API, kept for callers outside WP-8.6's files (MainViewModel, MainActivity): the
     * same round as [sync] with [SyncTrigger.MANUAL], folded into the old two-case result.
     */
    suspend fun syncNow(): SyncResult = when (val r = sync(SyncTrigger.MANUAL)) {
        RoundResult.Ok, RoundResult.NothingToPush -> SyncResult.Success
        RoundResult.InterlockRefused -> SyncResult.Error(INTERLOCK_REFUSED_MESSAGE)
        RoundResult.NeedsFirstConnectChoice -> SyncResult.Error(FIRST_CONNECT_MESSAGE)
        is RoundResult.Error -> SyncResult.Error(r.msg)
        null -> SyncResult.Error("A sync round is already running")
    }

    /** The round body without the lock. Persists the status (PX-49) on every outcome. */
    internal suspend fun runRound(): RoundResult {
        val result = try {
            syncNowUnchecked()
        } catch (e: InterlockRefused) {
            RoundResult.InterlockRefused
        } catch (e: CancellationException) {
            throw e
        } catch (e: OptiMergeTypeError) {
            RoundResult.Error(ErrorKind.MERGE_FAILED, "Merge operation failed: ${e.localizedMessage}")
        } catch (e: Exception) {
            RoundResult.Error(ErrorKind.INTERNAL, e.localizedMessage ?: e.toString())
        }
        try {
            when (result) {
                is RoundResult.Error -> if (result.kind != ErrorKind.NOT_LINKED) syncDataStore.saveSyncStatus(result.msg)
                RoundResult.InterlockRefused -> syncDataStore.saveSyncStatus(INTERLOCK_REFUSED_MESSAGE)
                else -> Unit // Ok / NothingToPush saved their status inside the round; first connect changes nothing
            }
        } catch (e: Exception) { }
        return result
    }

    /** One attempt's outcome: finished, or a transient HTTP error that restarts the attempt (sync.js:2484-2500). */
    private sealed interface Attempt {
        data class Done(val result: RoundResult) : Attempt
        data class Transient(val status: Int, val retryAfterMs: Long?, val text: String) : Attempt
    }

    private fun httpFailure(status: Int, retryAfterMs: Long?, text: String): Attempt =
        if (RetryPolicy.isTransient(status)) Attempt.Transient(status, retryAfterMs, text)
        else Attempt.Done(RoundResult.Error(ErrorKind.HTTP, text))

    private suspend fun syncNowUnchecked(): RoundResult {
        // REWORK R-3: Opti `syncNow` returns silently when `!cfg.enabled || !cfg.refreshToken`
        // (sync.js:2355-2358). A LOCAL DataStore read of the link flag only (no token read, no
        // refresh, no network), so D7 still holds: nothing reaches Dropbox. runRound does not save
        // NOT_LINKED. Disconnect, invalid_grant and the app-key migration all set it false. Not
        // AUTH_STATE: a pre-8.1 install has enabled tokens and no AUTH_STATE, and must still hit D7
        // and the legacy-app-key RECONNECT_REQUIRED rule below (AuthReviewFixesTest).
        if (!syncDataStore.isDropboxEnabled.first()) {
            return RoundResult.Error(ErrorKind.NOT_LINKED, "Dropbox is not linked or token is invalid")
        }
        // D7 next: before the token read and the pre-round refresh (a refresh is a network call).
        accountInterlock.assertAllowed()
        val accessToken = getActiveAccessToken()
            ?: return RoundResult.Error(ErrorKind.NOT_LINKED, "Dropbox is not linked or token is invalid")
        // Opti clears _conflictLogThrottle once per syncNow() (sync.js:2368): shared by the retries.
        val throttle = ConflictThrottle()
        var transientRetries = 0
        while (true) {
            when (val a = attempt(accessToken, throttle)) {
                is Attempt.Done -> return a.result
                is Attempt.Transient -> when (val d = RetryPolicy.decide(a.status, a.retryAfterMs, transientRetries)) {
                    is RetryPolicy.Decision.Retry -> { sleeper(d.waitMs); transientRetries++ }
                    is RetryPolicy.Decision.Abort -> return RoundResult.Error(ErrorKind.RATE_LIMITED, d.message)
                    RetryPolicy.Decision.GiveUp -> return RoundResult.Error(ErrorKind.HTTP, a.text)
                }
            }
        }
    }

    /** The base valid for (APP_KEY, linked account), parsed; null when none (or unparseable json). */
    private suspend fun readBase(): Pair<String, JsonObject?>? {
        val row = bases.validFor(DropboxConfig.appKey(), syncDataStore.getAccountId()) ?: return null
        val parsed = try { Json.parseToJsonElement(row.json) as? JsonObject } catch (e: Exception) { null }
        return row.rev to parsed
    }

    /** FIX 2026-07-12 (#5, Fraser-lite): a base whose rev matches but whose content differs is dropped (sync.js:2442-2447). */
    private fun selfCheck(base: JsonObject?, storedRev: String?, remote: DownloadResult.Ok?, remoteState: JsonObject?): JsonObject? {
        if (remote != null && !storedRev.isNullOrEmpty() && remote.rev == storedRev &&
            stable(remoteState) != stable(base)) return null
        return base
    }

    private fun stable(e: JsonElement?): String? = e?.let { StableJson.stableStringify(it) }

    private fun remoteDevOf(remote: DownloadResult.Ok?): String? =
        (remote?.json?.get("deviceId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** `ratchetHlc(_maxOrderingTs(remote.state))` (sync.js:2429); 120 s tolerance, refusal = clockSkew. */
    private fun ratchet(remoteState: JsonObject?) {
        if (remoteState == null) return
        hlc.ratchet(RoundMath.maxOrderingTs(remoteState))
    }

    /** Snapshot, self-checked base, merge and apply in ONE Room transaction. */
    private data class Merged(val local: JsonObject, val merged: JsonObject, val baseSnap: JsonObject?)

    private suspend fun mergeAndApply(
        baseSnap: JsonObject?,
        remote: DownloadResult.Ok?,
        remoteState: JsonObject?,
        ctx: MergeContext,
    ): Merged? {
        val events = mutableListOf<ConflictEvent>()
        val out = repository.inTransaction {
            // Phase B: localSavedAt is read BEFORE syncSubset() (sync.js:2437-2438).
            val localSavedAt = syncDataStore.localSavedAt.first()
            val local = localState.subset(baseSnap)
            val merged = if (remote != null && remoteState != null) {
                merger.merge(baseSnap, local, remoteState, ctx.copy(localSavedAt = localSavedAt.takeIf { it != 0L }),
                    ConflictSink { events.add(it) })
            } else local
            if (merged["tasks"] !is JsonArray) return@inTransaction null
            if (localState.applyDiff(local, merged)) repository.stampLocalSaved()
            events.forEach { repository.insertLog(it.toEventLogEntity(repository.physicalNow())) }
            Merged(local, merged, baseSnap)
        }
        return out
    }

    private suspend fun attempt(accessToken: String, throttle: ConflictThrottle): Attempt {
        // 1. Download (the D7 interlock runs inside DropboxService), then the corrupt check (PX-47).
        val remote: DownloadResult.Ok? = when (val dl = DropboxService.downloadState(accessToken)) {
            is DownloadResult.Ok -> dl
            DownloadResult.NotFound -> null
            is DownloadResult.Http -> return httpFailure(dl.status, dl.retryAfterMs, httpErrorText("download failed", dl.status, dl.summary))
            is DownloadResult.Corrupt -> return Attempt.Done(RoundResult.Error(ErrorKind.CORRUPT_REMOTE, dl.reason))
        }
        val remoteState = remote?.json?.get("state") as? JsonObject

        // 2. First-connect gate (PX-111): a remote with no base valid for this app key + account.
        val base = readBase()
        if (remote != null && base == null) return Attempt.Done(RoundResult.NeedsFirstConnectChoice)

        // 3. Ratchet, then AFTER_RATCHET (Opti: inbox consume, sync.js:2429-2434).
        ratchet(remoteState)
        val localDeviceId = syncDataStore.getOrCreateDeviceId()
        val round = RoundContext(
            remote = remote,
            local = JsonObject(emptyMap()),
            merged = null,
            ctx = MergeContext.forRound(
                localDev = localDeviceId,
                remoteDev = remoteDevOf(remote),
                nowPhysical = mergeClock(),
                localSavedAt = null,
                throttle = throttle,
            ),
        ).also { it.accessToken = accessToken }
        runSteps(StepSlot.AFTER_RATCHET, round)

        // 4. Snapshot, self-check, merge, apply (one transaction).
        val baseSnap = selfCheck(base?.second, base?.first, remote, remoteState)
        val m = try {
            mergeAndApply(baseSnap, remote, remoteState, round.ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Attempt.Done(RoundResult.Error(ErrorKind.MERGE_FAILED, "Merge operation failed: ${e.localizedMessage}"))
        } ?: return Attempt.Done(RoundResult.Error(ErrorKind.MERGE_FAILED, MERGE_INVALID_MESSAGE))
        round.local = m.local
        round.merged = m.merged

        // CR-KT-015: blobs go up BEFORE the state.json that names them (best effort).
        blobSync?.let { b -> bestEffort { b.uploadPending(accessToken) } }

        // 5. Freeze ONE serialization (sync.js:2462-2465): the upload body and the base.
        val mergedJson = CanonicalJson.canonicalJsonString(m.merged)
        val mergedStr = stable(m.merged)
        val nothingToPush = remote != null && baseSnap != null &&
            mergedStr == stable(remoteState) && mergedStr == stable(baseSnap)

        if (nothingToPush) {
            val baseOk = writeBaseSafe(remote!!.rev, mergedJson)
            syncDataStore.saveSyncStatus(if (baseOk) null else BASE_WRITE_FAILED_MESSAGE, wrapperClock())
            runSteps(StepSlot.POST_UPLOAD, round) // remote already equals what we hold (syncInboxFinish)
            if (!baseOk) return Attempt.Done(RoundResult.Error(ErrorKind.BASE_WRITE, BASE_WRITE_FAILED_MESSAGE))
            runPostSteps(round)
            return Attempt.Done(RoundResult.NothingToPush)
        }

        // 6. Upload with conflict retry (sync.js:2505-2556).
        return pushWithConflictRetry(round, accessToken, mergedJson, m.merged, remote?.rev, localDeviceId, throttle)
    }

    private suspend fun writeBaseSafe(rev: String, json: String): Boolean = try {
        bases.writeAtomic(DropboxConfig.appKey(), syncDataStore.getAccountId(), rev, json)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun pushWithConflictRetry(
        round: RoundContext,
        accessToken: String,
        initialJson: String,
        initialMerged: JsonObject,
        initialRev: String?,
        localDeviceId: String,
        throttle: ConflictThrottle,
    ): Attempt {
        var mergedJson = initialJson
        var merged = initialMerged
        var rev = initialRev
        var attempt = 0
        while (true) {
            val body = CanonicalJson.canonicalJsonString(wrap(merged, localDeviceId, wrapperClock()))
            when (val up = DropboxService.uploadState(accessToken, body, rev)) {
                is UploadOutcome.Ok -> {
                    val baseOk = writeBaseSafe(up.rev, mergedJson)
                    syncDataStore.saveSyncStatus(if (baseOk) null else BASE_WRITE_FAILED_MESSAGE, wrapperClock())
                    round.uploaded = true
                    round.merged = merged
                    runSteps(StepSlot.POST_UPLOAD, round)
                    if (!baseOk) return Attempt.Done(RoundResult.Error(ErrorKind.BASE_WRITE, BASE_WRITE_FAILED_MESSAGE))
                    runPostSteps(round)
                    return Attempt.Done(RoundResult.Ok)
                }
                UploadOutcome.Conflict -> {
                    if (attempt >= SYNC_CONFLICT_RETRY_LIMIT) {
                        // sync.js:2542-2548: soft lastError and a delayed retry (the scheduler arms it).
                        syncDataStore.saveSyncStatus(CONFLICT_MESSAGE)
                        onConflictExhausted?.invoke()
                        return Attempt.Done(RoundResult.Error(ErrorKind.CONFLICT, CONFLICT_MESSAGE))
                    }
                    val fresh: DownloadResult.Ok? = when (val dl = DropboxService.downloadState(accessToken)) {
                        is DownloadResult.Ok -> dl
                        DownloadResult.NotFound -> null
                        is DownloadResult.Http -> return httpFailure(dl.status, dl.retryAfterMs, httpErrorText("download failed", dl.status, dl.summary))
                        is DownloadResult.Corrupt -> return Attempt.Done(RoundResult.Error(ErrorKind.CORRUPT_REMOTE, dl.reason))
                    }
                    val freshState = fresh?.json?.get("state") as? JsonObject
                    // 2026-09-18 (round 2): ratchet BEFORE applying (sync.js:2526).
                    try { ratchet(freshState) } catch (e: Exception) { }
                    val base2 = readBase()
                    val baseSnap2 = selfCheck(base2?.second, base2?.first, fresh, freshState)
                    // Opti re-reads Date.now() in every merge() call: fresh physical now per retry.
                    val ctx2 = MergeContext.forRound(localDeviceId, remoteDevOf(fresh), mergeClock(), null, round.ctx.zone, throttle)
                    val m = try {
                        mergeAndApply(baseSnap2, fresh, freshState, ctx2)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return Attempt.Done(RoundResult.Error(ErrorKind.MERGE_FAILED, "Merge operation failed on conflict retry: ${e.localizedMessage}"))
                    } ?: return Attempt.Done(RoundResult.Error(ErrorKind.MERGE_FAILED, MERGE_INVALID_MESSAGE))
                    merged = m.merged
                    mergedJson = CanonicalJson.canonicalJsonString(merged)
                    rev = fresh?.rev
                    attempt += 1
                }
                is UploadOutcome.Http -> return httpFailure(up.status, up.retryAfterMs, httpErrorText("upload failed", up.status, up.summary))
            }
        }
    }

    private suspend fun runSteps(slot: StepSlot, round: RoundContext) {
        for (step in steps.filter { it.slot == slot }.sortedBy { it.order }) {
            try {
                step.run(round)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Opti catches each post-round step on its own (sync.js:2432-2434, :2376-2380).
            }
        }
    }

    /** EVENTS then BACKUPS: only after a round without lastError (sync.js:2376-2380). */
    private suspend fun runPostSteps(round: RoundContext) {
        runSteps(StepSlot.EVENTS, round)
        runSteps(StepSlot.BACKUPS, round)
        // CR-KT-015: retry the attachment blobs that were missing.
        blobSync?.let { b -> bestEffort { b.onRoundFinished(round.accessToken) } }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /** The built-in steps: event push/pull (8.7 replaces it) and the auto backup (8.9). */
    private inner class EventsStep : SyncRoundStep {
        override val slot = StepSlot.EVENTS
        override val order = 0
        override suspend fun run(r: RoundContext): StepResult {
            syncEventsPush(r.accessToken)
            syncEventsPull(r.accessToken)
            return StepResult.Continue
        }
    }

    private inner class BackupsStep : SyncRoundStep {
        override val slot = StepSlot.BACKUPS
        override val order = 0
        override suspend fun run(r: RoundContext): StepResult {
            maybeAutoExport(r.accessToken)
            return StepResult.Continue
        }
    }

    private val steps: List<SyncRoundStep> by lazy { roundSteps ?: listOf(EventsStep(), BackupsStep()) }

    // ---- SyncEngineInternals (for 8.9's force ops and first connect) ----

    override suspend fun download(): DownloadResult {
        accountInterlock.assertAllowed()
        val token = getActiveAccessToken() ?: return DownloadResult.Http(0, "not linked", null)
        return DropboxService.downloadState(token)
    }

    override suspend fun uploadFrozen(body: String, rev: String?): UploadOutcome {
        accountInterlock.assertAllowed()
        val token = getActiveAccessToken() ?: return UploadOutcome.Http(0, "not linked", null)
        val state = Json.parseToJsonElement(body) as JsonObject
        val wrapped = CanonicalJson.canonicalJsonString(wrap(state, syncDataStore.getOrCreateDeviceId(), wrapperClock()))
        return DropboxService.uploadState(token, wrapped, rev)
    }

    override suspend fun applyRemoteAsLocal(remote: JsonObject) {
        repository.inTransaction {
            val local = localState.subset(readBase()?.second)
            if (localState.applyDiff(local, remote)) repository.stampLocalSaved()
        }
    }

    override suspend fun writeBase(rev: String, body: String) {
        bases.writeAtomic(DropboxConfig.appKey(), syncDataStore.getAccountId(), rev, body)
    }

    /**
     * D6 / T4.2 — pushes this device's own uploadable events to Dropbox, one full-month-rebuild
     * JSON array file per touched UTC month. Spec §3 addendum rules 1-4, 20-23, 26.
     *
     * - Rule 1 (`evtUploadable`) / rule 22 (push watermark): only events with `ts >
     *   evtLastUploadTs` determine which UTC months are "touched" this push cycle.
     * - Rule 21 (full-month rebuild): for each touched month, the ENTIRE month's own-device,
     *   non-synthetic, non-lifecycle events (`evtOwnMonthRecords` -- no watermark filter) are
     *   re-fetched and the whole file is rebuilt/overwritten, never diffed/appended.
     * - Rule 3 (UTC month-key derivation) / rule 4 (`/events/<deviceId>-<YYYYMM>.json` path
     *   template) / rule 23 (Dropbox `overwrite` upload mode, no rev handshake) / rule 26
     *   (`evtLastUploadTs` persisted to DataStore, mirroring PWA's `syncCfgSave`).
     *
     * Deviation: PWA's `dbxUploadText` throws on HTTP failure, aborting the rest of the push
     * (leaving `evtLastUploadTs` untouched) before its final `syncCfgSave`. This project's
     * `DropboxService` convention returns a Boolean/null instead of throwing (see its class doc),
     * so an upload failure here returns early from the whole function the same way — no partial
     * watermark advance, matching PWA's all-or-nothing-per-push-attempt behavior.
     */
    private suspend fun syncEventsPush(accessToken: String) {
        val myDev = syncDataStore.getOrCreateDeviceId()
        val since = syncDataStore.evtLastUploadTs.first()

        val recentLogs = repository.getLogsSinceSync(since)
        val fresh = EventSyncEngine.evtUploadable(recentLogs, myDev, since)
        if (fresh.isEmpty()) return

        val months = fresh.map { EventSyncEngine.evtMonthKey(it.timestamp) }.distinct().sorted()
        var maxTs = since

        for (monthKey in months) {
            val (from, to) = EventSyncEngine.evtMonthRange(monthKey)
            val monthLogs = repository.getLogsInRangeSync(from, to)
            val recs = EventSyncEngine.evtOwnMonthRecords(monthLogs, myDev)
            if (recs.isEmpty()) continue

            val body = CanonicalJson.canonicalJsonString(JsonArray(recs.map { EventSyncEngine.buildEventPushJson(it) }))
            val path = "/events/$myDev-$monthKey.json"
            val ok = DropboxService.uploadOverwrite(accessToken, path, body) is UploadOutcome.Ok
            if (!ok) return // abort: mirror PWA's throw-aborts-before-watermark-save semantics

            recs.forEach { if (it.timestamp > maxTs) maxTs = it.timestamp }
        }

        eventWatermark.advanceTo(maxTs)
    }

    private val eventWatermark: EventWatermark by lazy { DataStoreEventWatermark(syncDataStore) }

    /**
     * D6 / T4.3 — pulls foreign devices' event files from `/events`, ingesting new/changed ones
     * via a per-file rev cache, deduped by `uid`. Spec §3 addendum rules 5-19, 24-28.
     *
     * - Rule 14/15 (pull throttle): no-op if called within `EVT_PULL_MIN_INTERVAL_MS` of the last
     *   successful pull.
     * - Rule 7/8 (`list_folder` + cursor pagination): drains every page before processing entries.
     * - Rule 9/10/11 (filename parse; skip non-matching names; skip own device).
     * - Rule 12 (skip whole months older than the age limit; drop their rev-cache entry too, so a
     *   never-again-relevant file doesn't keep a stale rev around forever).
     * - Rule 13 (skip unchanged rev).
     * - Rule 6 (rev-cache update, including the corrupt-file rev-clobber: rule 6's "overwrite
     *   `revs[ent.name] = dl.rev || ent.rev || null`" happens unconditionally per entry, even when
     *   the downloaded body fails to parse as JSON — sync.js's own comment: "Record the rev even
     *   if the file was corrupt -- its writer overwrites it on their next push; re-downloading a
     *   permanently bad file every minute helps no one.").
     * - Rule 16-19/25/27/28 (dedup by `uid` against both existing-in-DB-for-that-month and
     *   within-this-batch; ingest filter; strip `id`; insert, best-effort per record).
     */
    private suspend fun syncEventsPull(accessToken: String) {
        val now = transportClock()
        val lastPullAt = syncDataStore.evtLastPullAt.first()
        if (now - lastPullAt < EventSyncEngine.EVT_PULL_MIN_INTERVAL_MS) return

        val myDev = syncDataStore.getOrCreateDeviceId()
        val ageLimit = EventSyncEngine.EVENT_AGE_LIMIT_MS

        val entries = mutableListOf<DropboxService.FileEntry>()
        var page = DropboxService.listFolder(accessToken, "/events") ?: return
        entries.addAll(page.entries)
        var cursor = page.cursor
        while (page.hasMore && cursor != null) {
            page = DropboxService.listFolderContinue(accessToken, cursor) ?: break
            entries.addAll(page.entries)
            cursor = page.cursor
        }

        val revs = syncDataStore.evtFileRevs.first().toMutableMap()

        for (ent in entries) {
            val parsed = EventSyncEngine.evtParseFileName(ent.name) ?: continue
            val (parsedDev, parsedMonth) = parsed
            if (parsedDev == myDev) continue
            if (EventSyncEngine.evtMonthOlderThan(parsedMonth, now, ageLimit)) {
                revs.remove(ent.name)
                continue
            }
            if (ent.rev != null && revs[ent.name] == ent.rev) continue

            val dl = DropboxService.downloadFile(accessToken, "/events/${ent.name}") as? RawDownloadResult.Ok ?: continue
            val records: List<JsonObject>? = try {
                (Json.parseToJsonElement(dl.body) as? JsonArray)?.mapNotNull { it as? JsonObject }
            } catch (e: Exception) {
                null // corrupt: skip content, but still record the rev below (rule 6)
            }

            if (records != null) {
                val (from, to) = EventSyncEngine.evtMonthRange(parsedMonth)
                val existingUids = repository.getUidsInRangeSync(from, to).toSet()
                val toInsert = EventSyncEngine.evtIncomingFilter(records, existingUids, myDev, now, ageLimit)
                toInsert.forEach { obj ->
                    try {
                        repository.insertLog(EventSyncEngine.parseIncomingEventJson(obj))
                    } catch (e: Exception) {
                        // best-effort; matches evtInsertNew's per-record try/catch (rule 28)
                    }
                }
            }

            revs[ent.name] = dl.rev.ifEmpty { null } ?: ent.rev ?: ""
        }

        syncDataStore.saveEvtFileRevs(revs)
        syncDataStore.saveEvtLastPullAt(now)
    }

    // NOTE: this snapshot is only ever consumed field-by-field by
    // applyMergedSnapshotToDatabase() (Room writes), never re-serialized to a wire string today,
    // so it isn't on the hash-preimage / state.json / exportBackup routes T2.2 targets. Brought
    // into line with the same normative order + CanonicalJson.jsonNumber fixes as
    // exportBackup/buildLocalSnapshot anyway, defensively, since it's the identical bug pattern
    // and cheap to fix consistently -- not required for T2.2's acceptance criteria.
    //
    // Stage 3 / T3.1: routed through the shared SerializationEngine.build*Json helpers (instead
    // of locally-duplicated per-field builders). Since `data.tasks`/`data.rewards`/etc. already
    // carry `extraJson` (stamped by `importBackup`'s `parse*Entity` helpers), this now overlays
    // each record's original unmodeled fields back in at their original position, so
    // `importSnapshotFromFile` -> `applyMergedSnapshotToDatabase` round-trips them into Room
    // rather than silently dropping them at this intermediate JsonObject hop.
    private fun buildSnapshotFromBackup(data: BackupData): JsonObject {
        // Stage 3 / T3.3 (spec §7 D5): same fix as buildLocalSnapshot's charJson above -- a
        // backup-imported character's `updatedAt` must carry the value actually recorded in the
        // backup (already on `data.character.updatedAt` via SerializationEngine.parseCharacterEntity),
        // not a fresh "now" stamp minted at import time. A backup restore that later feeds a real
        // Dropbox sync (as this snapshot's "local" side) must not look artificially fresher than
        // it is for the exact same reason D5 targets the live sync path.
        val charJson = SerializationEngine.buildCharJson(data.character)

        val tasksJson = JsonArray(data.tasks.map { t -> SerializationEngine.buildTaskJson(t) })
        val rewardsJson = JsonArray(data.rewards.map { r -> SerializationEngine.buildRewardJson(r) })

        val deletionsJson = JsonArray(data.deletions.map { d ->
            buildJsonObject {
                put("id", JsonPrimitive(d.first))
                put("at", JsonPrimitive(d.second))
            }
        })

        // Stage 3 / T3.5 (D12): `version` removed here too, for field-set consistency with
        // `buildLocalSnapshot`/`mergeSnapshots` -- this function's result is only ever consumed
        // field-by-field by `applyMergedSnapshotToDatabase` (see this function's own leading
        // comment), so the extra key was harmless on that specific path, but leaving it in would
        // have meant this "same JsonObject shape" producer silently diverged from the other two
        // once they were aligned to the exact `syncSubset()` field list.
        return buildJsonObject {
            put("char", charJson)
            put("tasks", tasksJson)
            put("rewards", rewardsJson)
            put("tags", JsonArray(data.tags.map { t -> SerializationEngine.buildTagJson(t) }))
            put("devices", JsonArray(data.devices.map { d -> SerializationEngine.buildDeviceJson(d) }))
            put("lastCron", SerializationEngine.lastCronToJson(data.lastCron))
            put("history", JsonArray(data.globalHistory))
            // T3.1 opus-review FIX 3: shared overlay-aware builder (same rationale as tasks/
            // rewards/tags/devices above -- see buildLocalSnapshot's charHistoryJson).
            put("charHistory", JsonArray(data.charHistory.map { ch -> SerializationEngine.buildCharHistoryJson(ch) }))
            // Stage 3 / T3.2: `an` lifted to top level on THIS wire (matches buildLocalSnapshot /
            // mergeSnapshots -- see their comments); `data.views`/`data.metrics` came from the
            // backup's `prefs.an` (importBackup), so this is the nested->top-level placement swap
            // for the intermediate JsonObject that applyMergedSnapshotToDatabase parses.
            put("an", buildJsonObject {
                put("views", JsonArray(data.views.filter { it.source != null }.map { v -> SerializationEngine.buildViewJson(v) }))
                put("metrics", JsonArray(data.metrics))
            })
            put("monthlyBackups", JsonArray(data.monthlyBackups))
            put("deletions", deletionsJson)
        }
    }

    private suspend fun applyMergedSnapshotToDatabase(snap: JsonObject) {
        // Parse Character. T3.1 opus-review FIX 2 (minor): routed through the shared
        // SerializationEngine.parseCharacterEntity helper (instead of a locally-duplicated inline
        // construction) so `lastCron`/`dayStart`/`needsDeathAcknowledge`/`updatedAt` are actually
        // read from the merge winner's `char` object rather than silently reset to CharacterEntity's
        // constructor defaults on every single Dropbox merge-apply -- the inline version above never
        // set any of those four fields at all. Also now stamps `extraJson` consistently with
        // tasks/rewards/etc. (previously only `extraJson` itself was captured here).
        val charObj = snap["char"]?.jsonObject ?: buildJsonObject {}
        val character = SerializationEngine.parseCharacterEntity(charObj)
        repository.insertCharacter(character)

        // Parse Tasks. Stage 3 / T3.1: routed through the shared SerializationEngine.parseTaskEntity
        // helper (instead of a locally-duplicated one) so each task's `extraJson` is captured from
        // THIS merged/imported object -- i.e. the merge winner's full shape, including any
        // unmodeled field (`reminders[]`, `up`, `down`, ...) -- travels forward into Room rather
        // than being silently dropped at this write.
        // Stage 3 / T3.5 (D12, opus-review finding #3): `tasksList`'s array POSITION is the
        // authoritative order signal here -- `mergeSnapshots`' `mergeCollection` already builds
        // `snap["tasks"]` via "preserve local order, then append remote-only" (verified
        // byte-for-byte equivalent to PWA's `sync.js:795-799`), and `buildSnapshotFromBackup`
        // (the backup-import path into this same function) preserves the imported file's array
        // order via a plain `.map()`. So stamping each parsed task's persisted `orderIndex` from
        // its index in THIS list is what lets a later `buildLocalSnapshot`/`exportBackup`
        // re-emit tasks in the order this device actually last received them, instead of
        // re-deriving a fresh (and possibly wrong) order from `createdAt` every time (the actual
        // gap the opus review flagged: `TaskDao` had no memory of true array position at all).
        val tasksList = snap["tasks"]?.jsonArray ?: JsonArray(emptyList())
        val parsedTasks = tasksList.mapIndexed { idx, item ->
            SerializationEngine.parseTaskEntity(item as JsonObject).copy(orderIndex = idx.toLong())
        }

        // Delete any tasks no longer present in merged list
        val currentTasks = repository.allTasks.first()
        val mergedTaskIds = parsedTasks.map { it.id }.toSet()
        currentTasks.forEach { currentTask ->
            if (!mergedTaskIds.contains(currentTask.id)) {
                repository.deleteTaskById(currentTask.id)
            }
        }
        // Save merged tasks
        parsedTasks.forEach { repository.insertTask(it) }

        // Parse Rewards (Stage 3 / T3.1: shared helper, same extras rationale as tasks above)
        // Stage 3 / T3.5: same array-position-as-ordinal rationale as tasks above.
        val rewardsList = snap["rewards"]?.jsonArray ?: JsonArray(emptyList())
        val parsedRewards = rewardsList.mapIndexed { idx, item ->
            SerializationEngine.parseRewardEntity(item as JsonObject).copy(orderIndex = idx.toLong())
        }

        // Delete any rewards no longer present in merged list
        val currentRewards = repository.allRewards.first()
        val mergedRewardIds = parsedRewards.map { it.id }.toSet()
        currentRewards.forEach { currentReward ->
            if (!mergedRewardIds.contains(currentReward.id)) {
                repository.deleteRewardById(currentReward.id)
            }
        }
        // Save merged rewards
        parsedRewards.forEach { repository.insertReward(it) }

        // Parse and save Tags (Stage 3 / T3.1: shared helper)
        // Stage 3 / T3.5: same array-position-as-ordinal rationale as tasks above.
        val tagsList = snap["tags"]?.jsonArray ?: JsonArray(emptyList())
        val parsedTags = tagsList.mapIndexed { idx, item ->
            SerializationEngine.parseTagEntity(item as JsonObject).copy(orderIndex = idx.toLong())
        }
        val currentTags = repository.getAllTagsSync()
        val mergedTagIds = parsedTags.map { it.id }.toSet()
        currentTags.forEach { currentTag ->
            if (!mergedTagIds.contains(currentTag.id)) {
                repository.deleteTagById(currentTag.id)
            }
        }
        parsedTags.forEach { repository.insertTag(it) }

        // Parse and save Devices (Stage 3 / T3.1: shared helper)
        val devicesList = snap["devices"]?.jsonArray ?: JsonArray(emptyList())
        val parsedDevices = devicesList.map { item -> SerializationEngine.parseDeviceEntity(item as JsonObject) }
        parsedDevices.forEach { repository.insertDevice(it) }

        // Parse and save CharHistory (Stage 3 / T3.1 opus-review FIX 3: shared
        // SerializationEngine.parseCharHistoryEntity helper, same extras rationale as tasks above)
        val charHistoryList = snap["charHistory"]?.jsonArray ?: JsonArray(emptyList())
        val parsedCharHistory = charHistoryList.map { item -> SerializationEngine.parseCharHistoryEntity(item as JsonObject) }
        parsedCharHistory.forEach { repository.insertCharHistory(it) }

        // Parse and save Deletions (Tombstones)
        val deletionsList = snap["deletions"]?.jsonArray ?: JsonArray(emptyList())
        val parsedTombstones = deletionsList.map { item ->
            val obj = item as JsonObject
            TombstoneEntity(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                at = obj["at"]?.jsonPrimitive?.longOrNull ?: 0L // PX-20: never invented on parse
            )
        }
        parsedTombstones.forEach { repository.insertTombstone(it) }

        // WP-5.2 (spec §7 D9): persist the merged/imported `monthlyBackups` list locally so the
        // NEXT buildLocalSnapshot() call (this device's next sync round's "local" side, and
        // maybeAutoExport's own monthly gate check) sees THIS merge's authoritative result rather
        // than a stale/empty local cache. `snap["monthlyBackups"]` is top-level on THIS JsonObject
        // regardless of which caller produced it -- OptiSnapshotMerger.merge' Dropbox
        // merge-apply path (SyncManager.syncNow/pushStateWithConflictRetry) and
        // buildSnapshotFromBackup's backup-import path (importSnapshotFromFile) both funnel
        // through this same function, so this one write covers both.
        val monthlyBackupsList = (snap["monthlyBackups"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        syncDataStore.saveMonthlyBackups(monthlyBackupsList)

        // Parse and save `an.views`/`an.metrics` (Stage 3 / T3.2, spec §3 "an.views / an.metrics
        // shape"). `an` is top-level on THIS JsonObject regardless of which wire produced it
        // (buildLocalSnapshot/mergeSnapshots put it there directly; buildSnapshotFromBackup lifts
        // it there from the backup's `prefs.an` -- see that function's comment), so this single
        // parse covers both the Dropbox-merge and backup-import call paths to this function.
        val anObj = snap["an"]?.jsonObject
        val viewsList = anObj?.get("views")?.jsonArray ?: JsonArray(emptyList())
        val parsedViews = viewsList.map { item -> SerializationEngine.parseViewEntity(item as JsonObject) }

        // Delete any PWA-synced views no longer present in the merged/imported set. Never touch
        // native Kotlin-only "quick view" rows (`source == null`, see CustomViewEntity's class
        // doc) -- they aren't part of the PWA `an.views` collection at all, so their absence from
        // `snap["an"]["views"]` says nothing about whether they should still exist.
        val currentViews = repository.getAllCustomViewsSync()
        val mergedViewIds = parsedViews.map { it.id }.toSet()
        currentViews.forEach { cv ->
            if (cv.source != null && !mergedViewIds.contains(cv.id)) {
                repository.deleteCustomViewById(cv.id)
            }
        }
        parsedViews.forEach { repository.insertCustomView(it) }

        // Metrics: opaque passthrough (MetricsExtrasEntity) -- persist the merged/imported array
        // verbatim so a later export/re-sync re-emits it (no per-field Kotlin modeling exists to
        // "insert" individual metrics into).
        val metricsList = anObj?.get("metrics")?.jsonArray ?: JsonArray(emptyList())
        repository.saveMetricsExtrasJson(SerializationEngine.encodeJsonArray(metricsList))
    }

    suspend fun importSnapshotFromFile(jsonText: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val backup = SerializationEngine.importBackup(jsonText)
            val snap = buildSnapshotFromBackup(backup)
            applyMergedSnapshotToDatabase(snap)

            // WP-5.4 (Stage 4->5 carry-over, WORK_TRACKER.md line 55a): persist `backup.events`
            // into `event_logs` -- previously parsed but never written to Room, so an imported
            // backup's entire activity history was silently dropped (never in the Activity Feed,
            // never eligible for the Stage-4 `/events/*.json` cross-device sync pipeline). Scoped
            // strictly to this backup-FILE import path per the WP's guardrails: `snap`/
            // `buildSnapshotFromBackup`/`applyMergedSnapshotToDatabase` are NOT touched -- events
            // never travel on the `state.json` wire (spec §3 syncSubset table, line 247).
            //
            // Mirrors `syncEventsPull`'s two-layer dedup (spec §3 addendum rules 16-19):
            // month-bucketed existing-uid lookup (`EventLogDao.getUidsInRangeSync` via
            // `EventSyncEngine.evtMonthKey`/`evtMonthRange`, reused rather than loading the whole
            // table) plus an in-batch `seenInBatch` set, so re-importing the same backup twice --
            // or importing one whose events overlap with events already pulled live via
            // `/events/*.json` -- inserts zero duplicate rows (idempotent import). Uses
            // `insertImportedLog` (never `repository.insertLog`) so a foreign event's true
            // `uid`/`dev` (populated by `importBackup`'s WP-5.4 parsing fix) is never re-stamped to
            // this importing device. Per-event try/catch mirrors `syncEventsPull`'s best-effort
            // insert loop (rule 28) so one malformed event never aborts the rest of the import.
            val monthCache = mutableMapOf<String, Pair<Set<String>, Set<String>>>()
            val seenInBatch = mutableSetOf<String>()
            for (log in backup.events) {
                try {
                    val monthKey = EventSyncEngine.evtMonthKey(log.timestamp)
                    val (existingUids, existingLegacyKeys) = monthCache.getOrPut(monthKey) {
                        val (from, to) = EventSyncEngine.evtMonthRange(monthKey)
                        val logs = repository.getLogsInRangeSync(from, to)
                        logs.mapNotNull { it.uid }.toSet() to
                            logs.filter { it.uid == null }.map { it.legacyDedupKey() }.toSet()
                    }

                    val uid = log.uid
                    val dedupKey = uid ?: log.legacyDedupKey()

                    if (uid != null) {
                        if (existingUids.contains(uid) || seenInBatch.contains(uid)) continue
                    } else {
                        if (existingLegacyKeys.contains(dedupKey) || seenInBatch.contains(dedupKey)) continue
                    }

                    seenInBatch.add(dedupKey)
                    repository.insertImportedLog(log)
                } catch (e: Exception) {
                    // best-effort; matches syncEventsPull's per-record try/catch (rule 28)
                }
            }

            // Stage 3 / T3.1: persist the top-level backup `S` extras (prefs beyond `haptics`,
            // `__futureField`-style unknowns, `__seq`/`__hlcLast`/`__savedAt`) so a later
            // exportSnapshotToFile can overlay them back into place -- see SnapshotExtrasEntity's
            // class doc for why this is scoped to the backup-file path only.
            repository.saveSnapshotExtrasJson(SerializationEngine.encodeExtraJson(backup.rawSnapshot))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportSnapshotToFile(): Result<String> = withContext(Dispatchers.IO) {
        try {
            backupSource?.let { return@withContext Result.success(it.backupText(it.exporter(physicalClock))) }
            val localTasks = repository.allTasks.first()
            val localRewards = repository.allRewards.first()
            val localCharacter = repository.getCharacterSync()
            val localEvents = repository.allLogs.first()
            val deletions = repository.getAllTombstonesSync().map { it.id to it.at }
            val localTags = repository.getAllTagsSync()
            val localDevices = repository.getAllDevicesSync()
            val localCharHistory = repository.getAllCharHistorySync()
            // Stage 3 / T3.2: [exportBackup] itself filters to `source != null` rows -- pass the
            // full (possibly mixed native + PWA-synced) table through, same as
            // getAllCustomViewsSync()'s other callers.
            val localViews = repository.getAllCustomViewsSync()
            val localMetrics = SerializationEngine.parseJsonArray(repository.getMetricsExtrasJson())
                .mapNotNull { it as? JsonObject }

            val localSnap = buildLocalSnapshot(deletions)
            val lastCron = SerializationEngine.lastCronFromJson(localSnap["lastCron"])

            // Stage 3 / T3.1: last-known original top-level `S` extras, overlaid back into
            // position by exportBackup's `snapshotExtras` parameter (see SnapshotExtrasEntity's
            // class doc). Null on a fresh Kotlin-only install (nothing saved yet) -- exportBackup
            // falls back to its pre-T3.1 default `prefs` behavior in that case.
            val snapshotExtras = SerializationEngine.parseExtraJson(repository.getSnapshotExtrasJson())

            // WP-5.2 (spec §7 D9): thread the real persisted monthlyBackups list through so both
            // manual exports and auto-backups (maybeAutoExport reuses this same function) emit the
            // authoritative list, not exportBackup's snapshotExtras fallback overlay.
            val persistedMonthlyBackups: List<JsonElement> = syncDataStore.monthlyBackups.first().map { JsonPrimitive(it) }
            val hapticsEnabled = syncDataStore.hapticsEnabled.first()

            val result = SerializationEngine.exportBackup(
                character = localCharacter,
                tasks = localTasks,
                rewards = localRewards,
                events = localEvents,
                deletions = deletions,
                lastCron = lastCron,
                tags = localTags,
                devices = localDevices,
                charHistory = localCharHistory,
                views = localViews,
                metrics = localMetrics,
                monthlyBackups = persistedMonthlyBackups,
                snapshotExtras = snapshotExtras,
                hapticsEnabled = hapticsEnabled,
                nowPhysical = physicalClock(),
            )
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * WP-5.2 (spec §7 D9) -- port of PWA's `syncMaybeAutoExport` (sync.js:1348-1396). Called from
     * [pushStateWithConflictRetry]'s success branch, right before it returns [SyncResult.Success]
     * -- mirrors PWA's placement inside `syncNow()`'s own `.then()` success continuation
     * (sync.js:1030-1034), gated the same way ("only piggyback onto a sync that actually
     * succeeded"). Best-effort: ANY failure here (blob-build, upload, DataStore write) is caught
     * and swallowed so a successful ordinary sync is never turned into an error by a backup
     * hiccup -- exactly PWA's own top-level try/catch (sync.js:1349, 1395) around the entire
     * function body.
     *
     * Pure "is X needed" decisions live in [AutoBackupEngine] (unit-testable without Dropbox/
     * Robolectric); this function only orchestrates DataStore reads/writes, blob building (reusing
     * [exportSnapshotToFile] -- the same [SerializationEngine.exportBackup] entry point manual
     * export uses, no hand-built second envelope), and the Dropbox upload I/O.
     */
    suspend fun maybeAutoExport(accessToken: String) {
        try {
            val days = syncDataStore.exportIntervalDays.first()
            // PWA sync.js:1350-1351: `if(!days) return` -- 0 (or any falsy value) skips BOTH the
            // monthly and interval paths entirely; there is no separate monthly-only toggle.
            if (days <= 0) return

            val now = physicalClock()
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            // Device-default (local) calendar -- deliberately NOT UTC; see AutoBackupEngine's
            // class doc for why this must not reuse EventSyncEngine.evtMonthKey.
            val monthKey = AutoBackupEngine.localMonthKey(cal)

            val monthlyBackups = syncDataStore.monthlyBackups.first()
            val monthlyNeeded = AutoBackupEngine.isMonthlyBackupNeeded(monthKey, monthlyBackups)
            val lastExportTs = syncDataStore.lastExportTs.first()

            if (monthlyNeeded) {
                // Monthly path takes priority over interval (sync.js:1364 `if(monthlyBackupNeeded)
                // {...} else if(regularBackupNeeded){...}`) -- each branch builds its OWN blob
                // independently (sync.js:1370-1372 vs 1390-1391), no shared build across branches.
                val filename = AutoBackupEngine.monthlyBackupFilename(cal)
                val blob = exportSnapshotToFile().getOrNull() ?: return
                val ok = DropboxService.uploadOverwrite(accessToken, "/$filename", blob) is UploadOutcome.Ok
                if (ok) {
                    syncDataStore.saveLastExportTs(now)
                    // Re-check-then-append (sync.js:1378 `if (!S.monthlyBackups.includes(monthKey))`)
                    // -- re-reads rather than reusing the pre-upload snapshot, matching PWA exactly.
                    val current = syncDataStore.monthlyBackups.first()
                    if (monthKey !in current) {
                        syncDataStore.saveMonthlyBackups(current + monthKey)
                    }
                    // Only the monthly path logs an event on success (sync.js:1383-1385);
                    // `synthetic` stays false (default) -- unlike syncCompleted/clockSkew, this IS
                    // meant to reach the cross-device Activity Feed via the /events/*.json
                    // pipeline, same as PWA's own logEvent() call here.
                    repository.insertLog(
                        com.example.data.local.entities.EventLogEntity(
                            timestamp = repository.physicalNow(),
                            kind = "export",
                            taskTitle = "Monthly Auto Backup",
                            payloadJson = CanonicalJson.canonicalJsonString(buildJsonObject {
                                put("notes", JsonPrimitive("Saved monthly backup $filename to Dropbox"))
                            })
                        )
                    )
                }
                // On failure: do nothing further -- monthKey stays unwritten, so this retries on
                // every subsequent sync (mirrors PWA's implicit "failed monthly upload" behavior).
            } else if (AutoBackupEngine.isIntervalBackupNeeded(now, lastExportTs, days)) {
                val blob = exportSnapshotToFile().getOrNull() ?: return
                val ok = DropboxService.uploadOverwrite(accessToken, EXPORT_BACKUP_PATH, blob) is UploadOutcome.Ok
                if (ok) {
                    syncDataStore.saveLastExportTs(now)
                }
                // On failure: nothing beyond what the caller does -- retries next sync.
            }
        } catch (e: Exception) {
            // Best-effort only -- never let a backup failure flip a successful sync into an error.
        }
    }

    sealed class SyncResult {
        object Success : SyncResult()
        data class Error(val message: String) : SyncResult()
    }
}

/**
 * WP-5.3 (spec §7 D11) -- maps a collected [ConflictEvent] (accumulated during
 * [com.example.data.sync.merge.OptiSnapshotMerger]'s `onConflictResolved` callback, see [SyncManager.syncNow]/
 * [SyncManager.pushStateWithConflictRetry]) to the Room-insertable [EventLogEntity] that
 * `repository.insertLog` (called from the post-merge persistence step in both of those functions)
 * actually writes. `payloadJson` carries PWA's LITERAL wire field names -- `winner`/`loser`
 * always, plus `charId`/`charTitle`/`day` when present -- via `CanonicalJson.canonicalJsonString`
 * (same pattern `EventSyncEngine.parseIncomingEventJson` uses). `EXPORT_FIELD_MAP` already maps
 * all of `winner`/`loser`/`charTitle`/`day` (SerializationEngine.kt:110), and
 * `EventSyncEngine.buildEventPushJson` already spreads `payloadJson`'s keys onto the pushed event
 * JSON verbatim (its `knownKeys` set excludes these five names, so they round-trip through
 * `parseIncomingEventJson`'s `extraFields` -> `payloadJson` path on ingest too) -- no
 * schema/column changes needed for any of this.
 *
 * Top-level (not a `SyncManager` member) so it is directly unit-testable without a `SyncManager`
 * instance -- see `ConflictAuditEventTest`.
 *
 * `taskId`/`taskTitle` are left null for character conflicts (their [ConflictEvent] never sets
 * them -- see `OptiSnapshotMerger` (char branch)), so this never emits an empty/wrong
 * `taskId`/`taskTitle` wire key alongside `charId`/`charTitle`.
 *
 * `uid`/`dev` are left null -- `RpgRepository.insertLog` stamps them with THIS device's id when
 * absent, which is correct: a conflict-resolution event is generated locally, as a record of what
 * THIS device's merge decided, not a foreign event being replayed. `synthetic` stays at its
 * default `false` (only the pre-existing `clockSkew`/`syncCompleted` diagnostics are
 * `synthetic = true`; these new `conflictResolved`/`missReverted` events must be upload-eligible
 * per spec §3 addendum rule 1).
 */
fun ConflictEvent.toEventLogEntity(at: Long): EventLogEntity {
    // WP-8.5: every Opti payload key that is not an EventLogEntity column rides in payloadJson,
    // in Opti's literal order (winner, loser, winnerDev, loserDev, reason, charId, charTitle,
    // day). winnerDev/loserDev are LONG-FORM only (sync.js:1515-1520) and may be JSON null.
    val wire = toJson()
    val payload = JsonObject(wire.filterKeys { it !in COLUMN_KEYS })
    fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
        if (it.isString) it.content else it.toString()
    }
    return EventLogEntity(
        timestamp = at,
        kind = kind,
        taskType = str(taskType),
        taskId = str(taskId),
        taskTitle = str(taskTitle),
        payloadJson = if (payload.isEmpty()) null else CanonicalJson.canonicalJsonString(payload)
    )
}

private val COLUMN_KEYS = setOf("kind", "taskType", "taskId", "taskTitle")

private fun EventLogEntity.legacyDedupKey(): String =
    "$timestamp|$kind|$taskId|$taskType|$value|$cost|$streak|$damage|$direction|$reps|$taskTitle"
