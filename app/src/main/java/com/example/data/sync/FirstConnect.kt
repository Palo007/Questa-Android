package com.example.data.sync

import com.example.data.sync.auth.InterlockRefused
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * WP-8.9 (PX-111, D8) -- what the first-connect dialog shows. ONE rule for the engine and the
 * 8.14 UI: Push is refused iff ![pushAllowed] (an empty device must not wipe a real remote).
 */
data class FirstConnectPreview(
    val localTasks: Int,
    val localRewards: Int,
    val localLevel: Int,
    val localEmpty: Boolean,
    val remoteTasks: Int,
    val remoteRewards: Int,
    val remoteLevel: Int,
    val remoteEmpty: Boolean,
    val remoteSavedAt: Long,
) {
    val pushAllowed: Boolean get() = !(localEmpty && !remoteEmpty)
}

sealed interface FirstConnectChoice {
    object PullFromDropbox : FirstConnectChoice { override fun toString() = "PullFromDropbox" }
    object PushThisDevice : FirstConnectChoice { override fun toString() = "PushThisDevice" }
}

/**
 * WP-8.9 -- the answer to [SyncResult.NeedsFirstConnectChoice]. Pull = force pull (the remote
 * becomes local and the base); Push = force push (this device becomes the remote and the base),
 * refused with [EMPTY_LOCAL] and 0 uploads when ![FirstConnectPreview.pushAllowed].
 * Both run under [SyncLock]; the preview for the guard is re-read inside the lock.
 */
class FirstConnect(
    private val internals: SyncEngineInternals,
    private val forceOps: ForceOpsEngine,
    private val localSubset: suspend () -> JsonObject,
    /**
     * Review fix 1: the local side of the empty rule, REQUIRED (fail closed: no silent fallback to
     * the plain 0-tasks rule, which a seeded device never meets). Production passes
     * `LocalContent(repository)::isEffectivelyEmpty` (an untouched seeded state is empty).
     */
    private val isLocalEmpty: suspend () -> Boolean,
) {
    companion object {
        /** The refusal text of the empty-local guard (the card's `Refused(EMPTY_LOCAL)`). */
        const val EMPTY_LOCAL = "EMPTY_LOCAL"

        /** Is [r] the empty-local refusal of [resolveFirstConnect]? */
        fun isEmptyLocalRefusal(r: SyncResult): Boolean = r is SyncResult.Error && r.msg == EMPTY_LOCAL

        /** JS `Number(v) || 0` for the untouched rule. */
        private fun jsNum(e: kotlinx.serialization.json.JsonElement?): Double {
            val p = e as? JsonPrimitive ?: return if (e is JsonArray) (if (e.isEmpty()) 0.0 else Double.NaN) else Double.NaN
            if (p is JsonNull) return 0.0
            p.booleanOrNull?.let { return if (it) 1.0 else 0.0 }
            if (p.isString) {
                val t = p.content.trim()
                return if (t.isEmpty()) 0.0 else t.toDoubleOrNull() ?: Double.NaN
            }
            return p.doubleOrNull ?: Double.NaN
        }

        private fun orZero(d: Double) = if (d.isNaN() || d == 0.0) 0.0 else d

        /**
         * sync.js:2222 `untouched(c)`: `(Number(c.lvl)||0) <= 1 && (Number(c.xp)||0) <= 0 &&
         * (Number(c.gold)||0) <= 0 && (Number(c.maxHp)||50) <= 50`. A missing char is `{}`.
         */
        fun untouched(c: JsonObject?): Boolean {
            val o = c ?: JsonObject(emptyMap())
            val maxHp = jsNum(o["maxHp"]).let { if (it.isNaN() || it == 0.0) 50.0 else it }
            return orZero(jsNum(o["lvl"])) <= 1 && orZero(jsNum(o["xp"])) <= 0 &&
                orZero(jsNum(o["gold"])) <= 0 && maxHp <= 50
        }

        private fun count(state: JsonObject?, k: String): Int = (state?.get(k) as? JsonArray)?.size ?: 0

        private fun level(state: JsonObject?): Int = orZero(jsNum((state?.get("char") as? JsonObject)?.get("lvl"))).toInt()

        /** The preview from the two states (null remote = no remote file). */
        fun previewOf(local: JsonObject, remoteState: JsonObject?, remoteSavedAt: Long): FirstConnectPreview {
            val lt = count(local, "tasks")
            val lr = count(local, "rewards")
            val rt = count(remoteState, "tasks")
            val rr = count(remoteState, "rewards")
            return FirstConnectPreview(
                localTasks = lt,
                localRewards = lr,
                localLevel = level(local),
                localEmpty = lt == 0 && lr == 0 && untouched(local["char"] as? JsonObject),
                remoteTasks = rt,
                remoteRewards = rr,
                remoteLevel = level(remoteState),
                remoteEmpty = rt == 0 && rr == 0 && untouched(remoteState?.get("char") as? JsonObject),
                remoteSavedAt = remoteSavedAt,
            )
        }
    }

    /** The preview and the rev it was read at (null = no remote file). */
    private suspend fun readPreviewAt(): Pair<FirstConnectPreview, String?> {
        val local = localSubset()
        val (p, rev) = when (val dl = internals.download()) {
            is DownloadResult.Ok -> previewOf(
                local, dl.json["state"] as? JsonObject,
                ((dl.json["savedAt"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).toLong(),
            ) to dl.rev
            DownloadResult.NotFound -> previewOf(local, null, 0L) to null
            is DownloadResult.Http -> throw IllegalStateException(SyncManager.httpErrorText("download failed", dl.status, dl.summary))
            is DownloadResult.Corrupt -> throw IllegalStateException(dl.reason)
        }
        val empty = isLocalEmpty()
        return p.copy(localEmpty = empty) to rev
    }

    private suspend fun readPreview(): FirstConnectPreview = readPreviewAt().first

    /**
     * Review fix 1 (timing gap): the guard is checked on the SAME rev the upload is made against.
     * A conflict means the remote changed after the check, so the guard is read again (up to the
     * force-push retry limit). Caller holds the lock.
     */
    private suspend fun pushGuarded(): SyncResult {
        var attempt = 0
        while (true) {
            val (p, rev) = try { readPreviewAt() } catch (e: InterlockRefused) { throw e } catch (e: Exception) {
                return SyncResult.Error(ErrorKind.HTTP, "first connect failed: could not read Dropbox")
            }
            if (!p.pushAllowed) return SyncResult.Error(ErrorKind.INTERNAL, EMPTY_LOCAL)
            val r = forceOps.pushOnceAgainst(rev)
            if (r != null) return r
            if (attempt < ForceOpsEngine.CONFLICT_RETRY_LIMIT) { attempt++; continue }
            return forceOps.conflictExhausted()
        }
    }

    /** The dialog's numbers (downloads `/state.json` once, through the interlock). */
    suspend fun firstConnectPreview(): FirstConnectPreview =
        SyncLock.singleFlight(SyncTrigger.MANUAL) { readPreview() }!!

    /** Push -> `Error(INTERNAL, EMPTY_LOCAL)` with 0 uploads iff !pushAllowed; else a force push. Pull -> a force pull. */
    suspend fun resolveFirstConnect(c: FirstConnectChoice): SyncResult {
        val r = SyncLock.singleFlight(SyncTrigger.MANUAL) {
            try {
                when (c) {
                    FirstConnectChoice.PullFromDropbox -> forceOps.forcePullLocked()
                    FirstConnectChoice.PushThisDevice -> pushGuarded()
                }
            } catch (e: InterlockRefused) {
                SyncResult.InterlockRefused
            }
        }
        return r ?: SyncResult.Error(ErrorKind.INTERNAL, "A sync round is already running")
    }
}
