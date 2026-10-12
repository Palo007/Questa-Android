package com.example.data.inbox

import com.example.data.RpgRepository
import com.example.data.TapResult
import com.example.data.applyHabitTap
import com.example.data.boot.BootGate
import com.example.data.sync.EventWatermark
import com.example.data.sync.RoundContext
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncClocks
import com.example.data.sync.SyncRoundStep
import com.example.reminders.ReminderEngine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.util.Collections
import java.util.WeakHashMap

private fun errText(e: Throwable): String = e.message ?: e.toString()

/**
 * WP-8.8 (PX-58) -- Opti `syncInboxConsume` (sync.js:575-650), the AFTER_RATCHET step: after the
 * HLC ratchet and BEFORE the local snapshot, so every apply is part of `local` and rides in this
 * round's upload. The claimed paths that are safe to delete are handed to [InboxFinisher] through
 * [takeDone]; nothing is deleted here.
 *
 * Order, exactly as Opti:
 *  0. the boot gate blocks input -> `inboxSkip`, nothing is listed (the throttle is not touched);
 *     the throttle: less than 60 s since the last run -> nothing (INBOX_LAST_RUN_AT; a stored
 *     time later than now counts as 0);
 *  1. crash recovery: every `.json` already in `/inbox-claimed/<deviceId>/`;
 *  2. claim each new `.json` file of `/inbox` with `move_v2` (a lost claim is skipped);
 *  3. process one file at a time: download, [InboxValidator] (bad -> `/inbox-rejected/`), then
 *     `applyHabitTap` (the uid check is inside its transaction) and map the [TapResult]:
 *     - APPLIED / EVENT_ONLY -> watermark lowered to `ts - 1`, claimed, finished after the upload;
 *     - ALREADY_APPLIED -> finished (the finish-only pass after a crash);
 *     - UNKNOWN -> deferred when the habit is missing here but is a habit in the remote state
 *       ("habitNotMergedYet"), else rejected ("unknownHabit");
 *     - GATED / ERROR -> kept in the claimed folder for the next round (`inboxDefer`).
 *
 * Any exception out of the listing or claiming ends the step with no finished paths
 * (`inboxConsumeFailed`, sync.js:2435-2436); the claimed files stay and the next round recovers them.
 */
class InboxConsumer(
    private val repo: RpgRepository,
    private val w: EventWatermark,
    private val prefs: InboxPrefs,
    private val transport: (accessToken: String) -> InboxDropbox = { DropboxServiceInbox(it) },
    private val gateBlocks: () -> Boolean = { BootGate.blocksInput() },
    /** The tap `nowMs` (D23: physical). */
    private val tapNow: () -> Long = SyncClocks.tap,
    /** The 60 s throttle clock (Opti `Date.now()`). */
    private val throttleNow: () -> Long = SyncClocks.transport,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val diag: InboxDiag = InboxDiag.NONE,
    /** Test seam: the apply (default [applyHabitTap] with the same gate). */
    private val apply: suspend (com.example.data.TapRecord, Long) -> TapResult = { r, n -> repo.applyHabitTap(r, n, zone(), gateBlocks) },
) : SyncRoundStep {
    override val slot = StepSlot.AFTER_RATCHET
    override val order = 0

    private val doneByRound: MutableMap<RoundContext, List<String>> = Collections.synchronizedMap(WeakHashMap())

    /** The claimed paths of [r] that the finisher may delete; each list is handed out once. */
    fun takeDone(r: RoundContext): List<String> = doneByRound.remove(r) ?: emptyList()

    override suspend fun run(r: RoundContext): StepResult {
        val done = try {
            consume(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.push("inboxConsumeFailed", mapOf("error" to errText(e)))
            emptyList()
        }
        doneByRound[r] = done
        return StepResult.Continue
    }

    private data class Work(val name: String, val path: String)

    private fun isJson(name: String) = name.lowercase().endsWith(".json")

    internal suspend fun consume(r: RoundContext): List<String> {
        if (gateBlocks()) {
            diag.push("inboxSkip", mapOf("reason" to "bootGate"))
            return emptyList()
        }
        val nowMs = throttleNow()
        // Opti keeps `_inboxLastRunAt` in memory, so a reload resets it. The persisted value can
        // sit in the future after the wall clock was set ahead and then corrected; it counts as 0,
        // or the inbox would stay skipped until real time caught up (days, across restarts).
        val last = prefs.lastRunAt().let { if (it > nowMs) 0L else it }
        if (nowMs - last < InboxPaths.INBOX_MIN_INTERVAL_MS) return emptyList()
        prefs.setLastRunAt(nowMs)

        val dbx = transport(r.accessToken)
        val claimedDir = InboxPaths.claimedDir(repo.deviceIdForTaps())
        // 1. crash recovery: anything this device claimed but never finished
        val work = dbx.listFileNames(claimedDir).filter(::isJson).map { Work(it, "$claimedDir/$it") }.toMutableList()
        val seen = work.mapTo(HashSet()) { it.name }
        // 2. claim new files
        for (name in dbx.listFileNames(InboxPaths.INBOX_DIR).filter(::isJson)) {
            if (name in seen) continue
            val to = "$claimedDir/$name"
            val won = try {
                dbx.move("${InboxPaths.INBOX_DIR}/$name", to, autorename = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.push("inboxClaimFailed", mapOf("name" to name, "error" to errText(e)))
                continue
            }
            if (!won) { diag.push("inboxClaimLost", mapOf("name" to name)); continue }
            work.add(Work(name, to)); seen.add(name)
        }
        if (work.isEmpty()) return emptyList()

        val remoteHabits = remoteHabitIds(r)
        val done = ArrayList<String>()
        // 3. process, one file at a time
        for (wk in work) {
            val raw = try {
                dbx.downloadText(wk.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.push("inboxReadFailed", mapOf("name" to wk.name, "error" to errText(e)))
                continue
            } ?: continue // vanished between list and download
            val rec = InboxValidator.parse(wk.name, raw).getOrNull()
            if (rec == null) { reject(dbx, wk, "badRecord"); continue }
            val tap = rec.toTapRecord()
            val res = try {
                apply(tap, tapNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TapResult.ERROR // sync.js:627: `catch(err){ res = "error"; }`
            }
            when (res) {
                TapResult.APPLIED, TapResult.EVENT_ONLY -> {
                    // The event carries the real tap ts, which can sit below the event upload
                    // watermark: lower it (never raise it) so the next push rebuilds that month.
                    w.lowerTo(tap.ts - 1)
                    done.add(wk.path)
                    diag.push("inboxApplied", mapOf("name" to wk.name, "result" to if (res == TapResult.APPLIED) "applied" else "eventOnly"))
                }
                TapResult.ALREADY_APPLIED -> {
                    done.add(wk.path)
                    diag.push("inboxDup", mapOf("name" to wk.name))
                }
                TapResult.UNKNOWN -> {
                    val local = try { repo.getTaskByIdSync(rec.habitId) } catch (e: Exception) { null }
                    if (local == null && rec.habitId in remoteHabits) {
                        diag.push("inboxDefer", mapOf("name" to wk.name, "reason" to "habitNotMergedYet"))
                    } else {
                        reject(dbx, wk, "unknownHabit")
                    }
                }
                TapResult.GATED -> diag.push("inboxDefer", mapOf("name" to wk.name, "reason" to "gated"))
                TapResult.ERROR -> diag.push("inboxDefer", mapOf("name" to wk.name, "reason" to "error"))
            }
        }
        return done
    }

    /** sync.js:597-598: the ids of the remote state's habits (only read). */
    private fun remoteHabitIds(r: RoundContext): Set<String> {
        val tasks = ((r.remote?.json?.get("state") as? JsonObject)?.get("tasks") as? JsonArray) ?: return emptySet()
        return tasks.mapNotNullTo(HashSet()) { t ->
            val o = t as? JsonObject ?: return@mapNotNullTo null
            if ((o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "habit") return@mapNotNullTo null
            (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    /** sync.js:562-569 `_inboxReject`: move to `/inbox-rejected/` with autorename; a failure is only logged. */
    private suspend fun reject(dbx: InboxDropbox, wk: Work, reason: String) {
        try {
            dbx.move(wk.path, "${InboxPaths.INBOX_REJECTED_DIR}/${wk.name}", autorename = true)
            diag.push("inboxRejected", mapOf("name" to wk.name, "reason" to reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.push("inboxRejectFailed", mapOf("name" to wk.name, "reason" to reason, "error" to errText(e)))
        }
    }
}

/**
 * WP-8.8 (PX-58) -- Opti `syncInboxFinish` (sync.js:652-657), the POST_UPLOAD step: deletes the
 * claimed files of this round's [InboxConsumer]. The round runs POST_UPLOAD only after the state
 * upload succeeded (`r.uploaded`, sync.js:2483) or on the nothingToPush path, where the remote
 * already holds every consumed tap (sync.js:2478). A failed delete is only logged; the file is
 * re-processed next round as a duplicate (finish-only pass).
 */
class InboxFinisher(
    private val consumer: InboxConsumer,
    private val transport: (accessToken: String) -> InboxDropbox = { DropboxServiceInbox(it) },
    private val diag: InboxDiag = InboxDiag.NONE,
) : SyncRoundStep {
    override val slot = StepSlot.POST_UPLOAD
    override val order = 0

    override suspend fun run(r: RoundContext): StepResult {
        val paths = consumer.takeDone(r)
        if (paths.isEmpty()) return StepResult.Continue
        val dbx = transport(r.accessToken)
        for (p in paths) {
            try {
                dbx.delete(p)
                diag.push("inboxFinished", mapOf("path" to p))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.push("inboxFinishFailed", mapOf("path" to p, "error" to errText(e)))
            }
        }
        return StepResult.Continue
    }
}

/**
 * WP-8.8 (PX-60) -- Opti `syncInboxWriteMeta` (sync.js:666-676) and `syncInboxWriteReminders`
 * (sync.js:685-714), run after a round without lastError (sync.js:2381-2384; the round's
 * EVENTS/BACKUPS gate), last in the BACKUPS slot. Each file is uploaded (mode overwrite) only
 * when the hash of its list changed; the hash is saved only after the upload succeeded. The two
 * writers fail independently (`inboxMetaFailed` / `inboxRemindersFailed`).
 */
class InboxMetaWriter(
    private val repo: RpgRepository,
    private val prefs: InboxPrefs,
    private val transport: (accessToken: String) -> InboxDropbox = { DropboxServiceInbox(it) },
    /** `updatedAt: Date.now()` of both files (physical, like `wrap().savedAt`). */
    private val clock: () -> Long = SyncClocks.wrapperSavedAt,
    private val diag: InboxDiag = InboxDiag.NONE,
) : SyncRoundStep {
    override val slot = StepSlot.BACKUPS
    override val order = 100

    override suspend fun run(r: RoundContext): StepResult {
        val dbx = transport(r.accessToken)
        try {
            writeHabits(dbx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.push("inboxMetaFailed", mapOf("error" to errText(e)))
        }
        try {
            writeReminders(dbx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.push("inboxRemindersFailed", mapOf("error" to errText(e)))
        }
        return StepResult.Continue
    }

    /** true = uploaded; false = unchanged. */
    suspend fun writeHabits(dbx: InboxDropbox): Boolean {
        val habits = QuickLogMeta.habits(repo.getAllTasksSync())
        val hash = InboxHash.of(QuickLogMeta.habitsJson(habits))
        if (prefs.habitsHash() == hash) return false
        dbx.uploadText(InboxPaths.INBOX_META_PATH, QuickLogMeta.body(habits, clock()))
        prefs.setHabitsHash(hash)
        return true
    }

    /** true = uploaded; false = unchanged. */
    suspend fun writeReminders(dbx: InboxDropbox): Boolean {
        val items = ReminderEngine.items(repo.getAllTasksSync())
        val hash = InboxHash.of(InboxReminderMeta.itemsJson(items))
        if (prefs.remindersHash() == hash) return false
        dbx.uploadText(InboxPaths.INBOX_REMINDERS_PATH, InboxReminderMeta.body(items, clock()))
        prefs.setRemindersHash(hash)
        return true
    }
}

/** The three WP-8.8 steps for `SyncManager(roundSteps = \u2026)` (8.I wiring). */
object InboxSteps {
    fun of(repo: RpgRepository, w: EventWatermark, prefs: InboxPrefs, diag: InboxDiag = InboxDiag.NONE): List<SyncRoundStep> {
        val consumer = InboxConsumer(repo, w, prefs, diag = diag)
        return listOf(consumer, InboxFinisher(consumer, diag = diag), InboxMetaWriter(repo, prefs, diag = diag))
    }
}
