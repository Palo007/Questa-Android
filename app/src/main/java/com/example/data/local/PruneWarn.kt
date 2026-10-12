package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.dao.EventLogDao
import com.example.data.sync.events.EvtUploadPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CR-KT-014 (DIVERGENCE, Kotlin-only): warn before the CR-KT-013 prune deletes anything.
 * Invariant: a row is deleted only if the user was warned about THAT deletion >= 14 days earlier.
 *
 * Batching (user decision 2026-10-08): an age warning given at W covers every row with
 * ts < boundary = W + BATCH_MS + GRACE_MS - ageLimit. From W + 14 d on, each app start may run the
 * age pass with cutoff min(now - ageLimit, boundary), so covered rows go as they turn 18 months
 * old (CR-KT-013 timing), never before, never past the boundary. A new warning is given when an
 * uncovered row (ts >= boundary) comes within 14 days of the cutoff: about one warning per 30 days.
 * A cap warning is used once (after its grace) and bounded to rows that existed (max id).
 */
object PruneWarnPolicy {
    /** The warning comes this long before an uncovered event reaches the age cutoff. */
    const val WARN_LEAD_MS: Long = 14L * 86_400_000L
    /** Nothing a warning covers is deleted before the warning is this old. */
    const val GRACE_MS: Long = 14L * 86_400_000L
    /** One age warning covers this much more time, so warnings come about once a month. */
    const val BATCH_MS: Long = 30L * 86_400_000L
    /** 95% of [EventPruner.EVENT_HARD_CAP]. */
    const val WARN_COUNT: Int = 190_000

    /** Which rule needs a warning. [oldestUncovered]: oldest ts not covered by an age warning. null = none. */
    fun kinds(
        oldestUncovered: Long?,
        count: Int,
        now: Long,
        ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
        warnCount: Int = WARN_COUNT,
    ): PruneKinds? {
        val age = oldestUncovered != null && oldestUncovered < now - ageLimitMs + WARN_LEAD_MS
        val cap = count >= warnCount
        return if (age || cap) PruneKinds(age, cap) else null
    }

    fun windowOpen(
        oldestTs: Long?,
        count: Int,
        now: Long,
        ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
    ): Boolean = kinds(oldestTs, count, now, ageLimitMs) != null

    /** The first time anything a warning at [warnedAt] covers may be deleted. */
    fun pruneAt(warnedAt: Long): Long = warnedAt + GRACE_MS

    /** Rows with ts < this are covered by an age warning given at [warnedAt]. */
    fun boundaryFor(warnedAt: Long, ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS): Long =
        warnedAt + BATCH_MS + GRACE_MS - ageLimitMs

    /**
     * The age pass cutoff allowed at [now]: min(now - ageLimit, the boundary in force). The
     * newest warning's boundary is in force only after its grace; [AgeWarning.prevBoundary] (an
     * older warning whose grace had passed) stays in force meanwhile. null = no age pass.
     */
    fun ageCutoff(a: AgeWarning, now: Long, ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS): Long? {
        val inForce = listOfNotNull(a.prevBoundary, a.boundary.takeIf { now - a.at >= GRACE_MS }).maxOrNull()
            ?: return null
        return minOf(now - ageLimitMs, inForce)
    }
}

/** Which rule a warning covers. */
data class PruneKinds(val age: Boolean, val cap: Boolean)

/** An age warning seen at [at], covering rows with ts < [boundary]. */
data class AgeWarning(val at: Long, val boundary: Long, val prevBoundary: Long? = null)

/** A cap warning seen at [at]; its cap pass may remove only rows with id <= [maxId]. */
data class CapWarning(val at: Long, val maxId: Long?)

/** A banner warning queued (notifications off) but not yet seen. */
data class PendingBanner(val age: Boolean, val cap: Boolean, val maxId: Long?)

/** All device-only warning state. */
data class PruneWarnState(
    val age: AgeWarning? = null,
    val cap: CapWarning? = null,
    val pending: PendingBanner? = null,
    val bannerVisible: Boolean = false,
) {
    /** The state after the user saw a warning at [t] for these kinds. */
    fun withWarning(t: Long, ageKind: Boolean, capKind: Boolean, maxId: Long?, ageLimitMs: Long): PruneWarnState {
        val newAge = if (!ageKind) age else AgeWarning(
            at = t,
            boundary = PruneWarnPolicy.boundaryFor(t, ageLimitMs),
            prevBoundary = age?.let { old ->
                listOfNotNull(old.prevBoundary, old.boundary.takeIf { t - old.at >= PruneWarnPolicy.GRACE_MS }).maxOrNull()
            },
        )
        val newCap = if (!capKind) cap else CapWarning(t, maxId)
        return copy(age = newAge, cap = newCap)
    }

    /** The banner state saw the warning at [t]: the queued kinds become warnings. */
    fun bannerShown(t: Long, ageLimitMs: Long): PruneWarnState {
        val p = pending ?: return this
        return withWarning(t, p.age, p.cap, p.maxId, ageLimitMs).copy(pending = null, bannerVisible = true)
    }

    /** The text of the newest seen warning. */
    fun notice(): PruneNotice? {
        val a = age
        val c = cap
        // Text of the newest warning only (both given together: the age text).
        return when {
            a != null && (c == null || a.at >= c.at) -> PruneNotice(PruneWarnPolicy.pruneAt(a.at), a.boundary)
            c != null -> PruneNotice(PruneWarnPolicy.pruneAt(c.at), null)
            else -> null
        }
    }

    fun banner(): PruneBanner? = when {
        pending != null -> PruneBanner(pending, null)
        bannerVisible && notice() != null -> PruneBanner(null, notice())
        else -> null
    }
}

/** What a warning says: deletion starts at [startAt]; [boundary] null = cap only. */
data class PruneNotice(val startAt: Long, val boundary: Long?)

/** In-app banner: [pending] = queued, shown for the first time now; else [notice]. */
data class PruneBanner(val pending: PendingBanner?, val notice: PruneNotice?)

/** The user-facing strings (English). */
object PruneWarnText {
    const val ACTION = "Back up now"
    const val BODY = "Activity older than 18 months (or beyond 200,000 entries) is removed to keep the app fast. Back up now to keep a copy."
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun d(ms: Long, zone: ZoneId) = DATE.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun title(n: PruneNotice, zone: ZoneId = ZoneId.systemDefault()): String =
        if (n.boundary != null) "Old activity from before ${d(n.boundary, zone)} will be deleted, starting ${d(n.startAt, zone)}."
        else "The oldest activity will be deleted to stay under 200,000 entries, starting ${d(n.startAt, zone)}."

    /** After the start date the banner says the deletion is under way. */
    fun bannerTitle(n: PruneNotice, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = when {
        now < n.startAt -> title(n, zone)
        n.boundary != null -> "Old activity from before ${d(n.boundary, zone)} is being deleted."
        else -> "The oldest activity will be deleted soon."
    }
}

/**
 * Device-only warning state. Never synced, never exported: it lives in its own DataStore file
 * ([DataStorePruneWarnStore.FILE_NAME]) that no sync or export code reads, and the Android backup
 * rules exclude that file.
 */
interface PruneWarnStore {
    suspend fun read(): PruneWarnState
    suspend fun write(s: PruneWarnState)
    /** The banner was seen at [now]: queued kinds become warnings (grace starts). No-op if none queued. */
    suspend fun markBannerShown(now: Long)
    suspend fun dismissBanner()
    fun banner(): Flow<PruneBanner?>
}

private val Context.deviceMaintenanceStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStorePruneWarnStore.FILE_NAME
)

class DataStorePruneWarnStore(
    private val ds: DataStore<Preferences>,
    private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
) : PruneWarnStore {

    private fun Preferences.state() = PruneWarnState(
        age = this[AGE_AT]?.let { at -> this[AGE_BOUNDARY]?.let { AgeWarning(at, it, this[AGE_PREV]) } },
        cap = this[CAP_AT]?.let { CapWarning(it, this[CAP_MAX_ID]) },
        pending = if (this[PENDING] == true) PendingBanner(this[PENDING_AGE] == true, this[PENDING_CAP] == true, this[PENDING_MAX_ID]) else null,
        bannerVisible = this[VISIBLE] == true,
    )

    private fun <T> MutablePreferences.put(k: Preferences.Key<T>, v: T?) { if (v != null) this[k] = v else remove(k) }

    private fun MutablePreferences.put(s: PruneWarnState) {
        put(AGE_AT, s.age?.at); put(AGE_BOUNDARY, s.age?.boundary); put(AGE_PREV, s.age?.prevBoundary)
        put(CAP_AT, s.cap?.at); put(CAP_MAX_ID, s.cap?.maxId)
        put(PENDING, if (s.pending != null) true else null)
        put(PENDING_AGE, s.pending?.age); put(PENDING_CAP, s.pending?.cap); put(PENDING_MAX_ID, s.pending?.maxId)
        put(VISIBLE, if (s.bannerVisible) true else null)
    }

    override suspend fun read(): PruneWarnState = ds.data.first().state()
    override suspend fun write(s: PruneWarnState) { ds.edit { it.put(s) } }
    override suspend fun markBannerShown(now: Long) { ds.edit { it.put(it.state().bannerShown(now, ageLimitMs)) } }
    override suspend fun dismissBanner() { ds.edit { it.put(it.state().copy(bannerVisible = false)) } }
    override fun banner(): Flow<PruneBanner?> = ds.data.map { it.state().banner() }

    companion object {
        const val FILE_NAME = "questa_device_maintenance"
        private val AGE_AT = longPreferencesKey("prune_age_warned_at")
        private val AGE_BOUNDARY = longPreferencesKey("prune_age_boundary")
        private val AGE_PREV = longPreferencesKey("prune_age_prev_boundary")
        private val CAP_AT = longPreferencesKey("prune_cap_warned_at")
        private val CAP_MAX_ID = longPreferencesKey("prune_cap_max_id")
        private val PENDING = booleanPreferencesKey("prune_banner_pending")
        private val PENDING_AGE = booleanPreferencesKey("prune_banner_pending_age")
        private val PENDING_CAP = booleanPreferencesKey("prune_banner_pending_cap")
        private val PENDING_MAX_ID = longPreferencesKey("prune_banner_pending_max_id")
        private val VISIBLE = booleanPreferencesKey("prune_banner_visible")

        fun forApp(ctx: Context): DataStorePruneWarnStore =
            DataStorePruneWarnStore(ctx.applicationContext.deviceMaintenanceStore)
    }
}

/** Shows the warning. true = the user was notified now; false = use the in-app banner. */
fun interface PruneWarner {
    suspend fun warn(notice: PruneNotice): Boolean
}

/**
 * The CR-KT-014 gate around the CR-KT-013 prune. One [run] per app session (via [EventPruneTrigger]).
 * 1. Run only what warnings allow: the age pass up to [PruneWarnPolicy.ageCutoff]; the cap pass
 *    (bounded by max id) once, after the cap warning's grace, then the cap warning is cleared.
 * 2. An age warning is cleared when all rows it covers are past the age limit (now - ageLimit >= boundary).
 * 3. Warn when an uncovered row is within 14 days of the cutoff, or count >= 190000 with no cap
 *    warning. Notification posted -> stored now; else the banner is queued and stored when seen.
 *    Nothing new while a banner is still queued.
 * Debug clock offset != 0: nothing at all.
 */
class WarnedEventPrune(
    private val dao: EventLogDao,
    private val store: PruneWarnStore,
    private val warner: PruneWarner,
    private val now: () -> Long,
    private val prune: suspend (ageCutoff: Long?, capMaxId: Long?) -> Int,
    private val clockOffsetMs: () -> Long = { 0L },
    private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
    private val warnCount: Int = PruneWarnPolicy.WARN_COUNT,
) {
    enum class Outcome { SKIPPED_OFFSET, IDLE, WARNED, AWAITING_BANNER, PRUNED }

    suspend fun run(): Outcome {
        if (clockOffsetMs() != 0L) return Outcome.SKIPPED_OFFSET
        val t = now()
        val start = store.read()
        var s = start

        val ageCut = s.age?.let { PruneWarnPolicy.ageCutoff(it, t, ageLimitMs) }
        val capDue = s.cap?.takeIf { t - it.at >= PruneWarnPolicy.GRACE_MS }
        val capMaxId = capDue?.maxId
        val pruned = ageCut != null || capMaxId != null
        if (pruned) prune(ageCut, capMaxId)
        if (capDue != null) s = s.copy(cap = null)
        s.age?.let { if (t - ageLimitMs >= it.boundary) s = s.copy(age = null) }
        if (s != start) store.write(s)
        val afterPrune = s

        var warned = false
        val k = PruneWarnPolicy.kinds(
            dao.oldestTimestampFrom(s.age?.boundary ?: Long.MIN_VALUE),
            if (s.cap == null) dao.countAll() else 0,
            t, ageLimitMs, warnCount,
        )
        // A queued, unseen banner whose reason is gone (e.g. after a reset) is dropped.
        if (s.pending != null && k == null) s = s.copy(pending = null)
        if (s.pending == null) {
            if (k != null) {
                val maxId = if (k.cap) dao.maxId() else null
                val seen = s.withWarning(t, k.age, k.cap, maxId, ageLimitMs)
                // Shown BEFORE it is stored: a failed warning never starts a grace.
                s = if (warner.warn(seen.notice()!!)) seen else s.copy(pending = PendingBanner(k.age, k.cap, maxId))
                warned = true
            }
        }
        if (s != afterPrune) store.write(s)
        return when {
            pruned -> Outcome.PRUNED
            warned -> Outcome.WARNED
            s.pending != null -> Outcome.AWAITING_BANNER
            else -> Outcome.IDLE
        }
    }
}
