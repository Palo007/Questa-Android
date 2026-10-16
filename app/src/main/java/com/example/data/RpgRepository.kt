package com.example.data

import com.example.data.local.dao.CharacterDao
import com.example.data.local.dao.EventLogDao
import com.example.data.local.dao.RewardDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.dao.TagDao
import com.example.data.local.dao.DeviceDao
import com.example.data.local.dao.CharHistoryDao
import com.example.data.local.dao.TombstoneDao
import com.example.data.local.dao.SnapshotExtrasDao
import com.example.data.local.dao.MetricsExtrasDao
import com.example.data.local.entities.SnapshotExtrasEntity
import com.example.data.local.entities.MetricsExtrasEntity
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.dao.CustomViewDao
import com.example.data.clock.HlcClock
import com.example.data.clock.ProcessHlc
import com.example.data.local.AppDatabase
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.sync.BaseStore
import com.example.data.sync.RoomBaseStore
import com.example.data.sync.SyncDataStore
import androidx.room.withTransaction
import com.example.data.clock.DayStamp
import com.example.data.local.dao.CronInputs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * CR-KT-022: the outcome of one [RpgRepository.backfillCompletionHistory] run.
 *
 * [restored] counts the completed day-records added to task histories (0 = nothing to do).
 * [hadEvents] says whether the local completion log had any rows at all. The boot hook's
 * one-shot stamp keys on it: an empty log means nothing pre-1.3 can exist to repair, so the
 * flag stays unset and later boots re-check (free on an empty table) until the log has
 * content -- from then on the flag stops the re-read of a growing log.
 */
data class CompletionBackfillReport(val restored: Int, val hadEvents: Boolean)

/** CR-KT-023: one [RpgRepository.backfillRepsHistory] run -- [replayed] rows, [tasks] changed. */
data class RepsReplayReport(val replayed: Int, val tasks: Int)

class RpgRepository(
    private val characterDao: CharacterDao,
    private val taskDao: TaskDao,
    private val rewardDao: RewardDao,
    private val eventLogDao: EventLogDao,
    private val tagDao: TagDao,
    private val deviceDao: DeviceDao,
    private val charHistoryDao: CharHistoryDao,
    private val tombstoneDao: TombstoneDao,
    private val customViewDao: CustomViewDao,
    private val snapshotExtrasDao: SnapshotExtrasDao,
    private val metricsExtrasDao: MetricsExtrasDao,
    // Stage 3 / T3.4 (spec §7 D8): needed so every method below that durably persists local state
    // can stamp the real wall-clock instant of that save, mirroring PWA's `save()` chokepoint
    // (app.js:233-272, `S.__savedAt = now()` on EVERY local persist, unconditionally -- including
    // while a merge is being applied, not just genuine user edits; see `markLocalSaved()` below).
    private val syncDataStore: SyncDataStore,
    // WP-8.6: the Room database, for one-transaction reads+writes (the merge apply, applyHabitTap)
    // and the sync base. Optional so older call sites keep compiling; without it the writes are
    // not atomic and the round falls back to AppDatabase.getDatabase(context) for the base.
    private val database: AppDatabase? = null,
    // WP-8.6 (PX-46): the HLC that stamps localSavedAt. Defaults to the process HLC.
    hlc: HlcClock? = null,
    // CR-KT-023: the device-only bulk-reps replay state (boundary id + done flag). null (tests,
    // SyncWorker, the attachment fallback): addHabitReps pins nothing and the replay does nothing.
    private val repsReplayStore: com.example.data.local.RepsReplayStore? = null,
) {
    private val hlcOverride = hlc

    /** WP-8.6: the process HLC (Opti's one `lastIssued`, app.js:131-165), or the injected one. */
    val hlcClock: HlcClock get() = hlcOverride ?: ProcessHlc.get(syncDataStore)

    /** WP-8.6: the Room base store of [database], if one was given. */
    val baseStore: BaseStore? = database?.let { RoomBaseStore(it.syncBaseDao()) }

    /** WP-8.6: runs [block] in ONE Room transaction (nested calls join it). No database: runs it as is. */
    suspend fun <R> inTransaction(block: suspend () -> R): R {
        val db = database ?: return block()
        return db.withTransaction { block() }
    }

    /**
     * WP-8.6 (PX-42, SY-04): Opti `SYNC_APPLYING` (sync.js:831-897). Writes made inside [block]
     * neither stamp localSavedAt nor emit [localWrites], so applying a merge never schedules the
     * next sync. The caller stamps localSavedAt once if the apply changed anything
     * ([stampLocalSaved]), as Opti's syncApply calls save() only when `_changed`.
     */
    suspend fun <R> syncApplying(block: suspend () -> R): R = withContext(SyncApplyingElement) { block() }

    /** Opti save(): `S.__savedAt = now()` (app.js:353), without scheduling a sync. */
    suspend fun stampLocalSaved() {
        syncDataStore.saveLocalSavedAt(hlcClock.now())
    }

    private object SyncApplyingElement : AbstractCoroutineContextElement(SyncApplyingKey)
    private object SyncApplyingKey : CoroutineContext.Key<SyncApplyingElement>
    // Stage 3 / T3.4 (spec §7 D8): the stale-local guard (SerializationEngine.mergeCollection's
    // GUARD 3, mirroring sync.js:699-716) needs `localSavedAt` to be the REAL instant of the last
    // successful local save, not a value stamped once per Dropbox sync round (previously
    // SyncManager only called `saveLocalSavedAt` after a successful upload, using the sync's own
    // HLC watermark -- see SyncManager.kt's removed post-upload stamp). Without this, a crash
    // between an actual local Room write and the next sync could leave `localSavedAt` describing
    // a moment that never truly reflects "local genuinely knew about this record's current state
    // as of now", which is exactly the guard's precondition for treating a local disagreement as
    // authoritative rather than a stale/reverted read.
    private suspend fun markLocalSaved() {
        // WP-8.6: skipped while a merge is applied (Opti SYNC_APPLYING); stamped from the HLC,
        // as Opti's `S.__savedAt = now()` (app.js:353). See WP-8.6_NOTES.md, "localSavedAt".
        if (currentCoroutineContext()[SyncApplyingKey] != null) return
        syncDataStore.saveLocalSavedAt(hlcClock.now())
        _localWrites.emit(Unit)
    }

    // WP-5.1 (spec §7 D10; PWA `save()`, app.js:277, calling `scheduleSync()`) -- write-signal
    // source for SyncScheduler's 5s debounce trigger. Every one of markLocalSaved()'s existing
    // call sites (every durable local write this repository performs) gets this signal for free,
    // with no per-call-site change, since markLocalSaved() is the single chokepoint they all
    // already funnel through. `extraBufferCapacity = 1` + `DROP_OLDEST` mirrors the debounce's own
    // "only the latest write matters, we just need to know one happened" semantics -- a slow
    // collector never blocks a write, and a burst of writes before the collector catches up
    // collapses to "at least one write happened", which is all the debounce timer needs.
    private val _localWrites = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val localWrites: SharedFlow<Unit> = _localWrites.asSharedFlow()

    val character: Flow<CharacterEntity?> = characterDao.getCharacter()
    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val allRewards: Flow<List<RewardEntity>> = rewardDao.getAllRewards()
    val allLogs: Flow<List<EventLogEntity>> = eventLogDao.getAllLogs()
    val allTags: Flow<List<TagEntity>> = tagDao.getAllTags()
    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevices()
    val allCharHistory: Flow<List<CharHistoryEntity>> = charHistoryDao.getAllCharHistory()
    val allTombstones: Flow<List<TombstoneEntity>> = tombstoneDao.getAllTombstones()
    val allCustomViews: Flow<List<CustomViewEntity>> = customViewDao.getAllCustomViews()

    suspend fun getAllCustomViewsSync(): List<CustomViewEntity> = customViewDao.getAllCustomViewsSync()
    suspend fun insertCustomView(customView: CustomViewEntity) {
        customViewDao.insertCustomView(customView)
        markLocalSaved()
    }
    suspend fun deleteCustomViewById(id: String) {
        customViewDao.deleteCustomViewById(id)
        markLocalSaved()
    }

    suspend fun getCharacterSync(): CharacterEntity {
        // [DEFERRED_OPEN_QUESTION] review 49 minor: Opti's fresh character has no `updatedAt`.
        // `character.updatedAt` is a NOT NULL Room column and is always on the wire here, so
        // omitting it needs a schema change; kept as an HLC stamp (the character's write clock).
        return characterDao.getCharacterSync() ?: CharacterEntity(updatedAt = hlcClock.now()).also {
            characterDao.insertCharacter(it)
            markLocalSaved()
        }
    }

    suspend fun insertCharacter(character: CharacterEntity) {
        characterDao.insertCharacter(character)
        markLocalSaved()
    }

    // Stage 3 / T3.5 (D12, opus-review finding #3): the ordinal a genuinely NEW, locally-created
    // task must receive so it sorts BEFORE every existing one -- mirroring PWA's
    // `S.tasks.unshift(EDIT)` (app.js:4545). Callers that construct a fresh `TaskEntity` for a
    // real user-initiated creation (e.g. `MainViewModel.createTask`) must fetch this and pass it
    // as the entity's `orderIndex`; callers that upsert an EXISTING or merge-derived task (e.g.
    // `SyncManager.applyMergedSnapshotToDatabase`) must NOT call this -- they assign `orderIndex`
    // from the record's own position in the already-correctly-ordered merged/imported array
    // instead (see that function's comment).
    suspend fun nextTaskOrderIndex(): Long = (taskDao.getMinOrderIndex() ?: 0L) - 1

    suspend fun insertTask(task: TaskEntity) {
        taskDao.insertTask(task)
        markLocalSaved()
    }
    /**
     * The UI's whole-task save. CR-KT-015: `attachments` belong to [com.example.data.attachments.AttachmentRepository]
     * only, so the row's CURRENT attachments are kept (read and written in ONE Room transaction, so an add or
     * remove that lands while an editor holds an older snapshot is never undone). Sync, merge and import never
     * come here: they write through [insertTask] with the attachments from the wire.
     */
    suspend fun updateTask(task: TaskEntity) {
        inTransaction {
            val stored = taskDao.getTaskByIdSync(task.id)
            taskDao.updateTask(if (stored == null) task else task.copy(attachments = stored.attachments))
        }
        markLocalSaved()
    }

    /** CR-KT-015: the attachment repository's write; the only local path that sets `attachments`. */
    suspend fun updateTaskWithAttachments(task: TaskEntity) {
        taskDao.updateTask(task)
        markLocalSaved()
    }
    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteWithTombstone(task.id, hlcClock.now())
        markLocalSaved()
    }
    suspend fun deleteTaskById(id: String) {
        taskDao.deleteWithTombstone(id, hlcClock.now())
        markLocalSaved()
    }
    suspend fun getAllTasksSync(): List<TaskEntity> = taskDao.getAllTasksSync()

    /**
     * Bulk Selection Mode contract `bulk-select-v1` (data half): tag edits over [ids].
     *
     * Each id is a per-task diff against that task's own `tags`: tokens in [add] already present
     * are kept in place (never duplicated), tokens in [remove] that are absent are a no-op for
     * that task. Surviving tags keep their order; genuinely new tokens are appended in [add]'s
     * iteration order. Unknown ids are skipped.
     *
     * Wire parity: every found task is rewritten with a fresh [HlcClock.now] `updatedAt` (one
     * stamp per task, as N sequential single edits would issue) and is otherwise byte-identical
     * -- including an already-correct task, which still gets its `updatedAt` bumped. `extraJson`
     * is never touched. The whole batch lands in ONE Room transaction ([inTransaction]), and
     * `localSavedAt` is stamped once afterwards, as PWA `save()` stamps unconditionally.
     */
    suspend fun bulkUpdateTags(ids: List<String>, add: Set<String>, remove: Set<String>) {
        // CR-KT-008 (KT-95): [add]/[remove] are tag IDS. A task may still hold a legacy NAME
        // token (pre-fix Kotlin editor): remove drops either spelling, add skips a tag the task
        // already holds by name. Other tokens are left exactly as stored (no mass rewrite).
        val idByName = tagDao.getAllTagsSync().associate { it.name to it.id }
        inTransaction {
            ids.forEach { id ->
                val task = taskDao.getTaskByIdSync(id) ?: return@forEach
                val held = task.tags.map { idByName[it] ?: it }.toSet()
                val kept = task.tags.filter { it !in remove && (idByName[it] ?: it) !in remove }
                val appended = add.filter { it !in task.tags && it !in held }
                taskDao.updateTask(task.copy(tags = kept + appended, updatedAt = hlcClock.now()))
            }
        }
        markLocalSaved()
    }

    /**
     * Bulk Selection Mode contract `bulk-select-v1` (data half): difficulty overwrite over [ids].
     *
     * [difficulty] must be one of `trivial`/`easy`/`medium`/`hard` (enforced with `require`).
     * Every found task is rewritten with the new difficulty and a fresh [HlcClock.now]
     * `updatedAt`, otherwise byte-identical; unknown ids are skipped. ONE Room transaction
     * ([inTransaction]); `localSavedAt` stamped once afterwards.
     */
    suspend fun bulkSetDifficulty(ids: List<String>, difficulty: String) {
        require(difficulty in setOf("trivial", "easy", "medium", "hard")) {
            "bulkSetDifficulty: unknown difficulty \"$difficulty\" (expected one of trivial, easy, medium, hard)"
        }
        inTransaction {
            ids.forEach { id ->
                val task = taskDao.getTaskByIdSync(id) ?: return@forEach
                taskDao.updateTask(task.copy(difficulty = difficulty, updatedAt = hlcClock.now()))
            }
        }
        markLocalSaved()
    }

    /**
     * Bulk Selection Mode contract `bulk-select-v1` (data half): tombstoning delete over [ids].
     *
     * Per id this is exactly the single-task path ([deleteTaskById]): [TaskDao.deleteWithTombstone]
     * with a fresh [HlcClock.now] stamp per id, so each delete outranks the edit before it (Opti
     * `delMark`, app.js:194-201). ONE Room transaction ([inTransaction]); `localSavedAt` stamped
     * once afterwards.
     */
    suspend fun bulkDelete(ids: List<String>) {
        inTransaction {
            ids.forEach { id -> taskDao.deleteWithTombstone(id, hlcClock.now()) }
        }
        markLocalSaved()
    }

    // Stage 3 / T3.5: the ordinal a genuinely NEW, locally-created reward must receive so it sorts
    // AFTER every existing one -- mirroring PWA's `S.rewards.push(REDIT)` (app.js:4763). Same
    // caller contract as `nextTaskOrderIndex()` (new-creation callers only, not merge-apply).
    suspend fun nextRewardOrderIndex(): Long = (rewardDao.getMaxOrderIndex() ?: 0L) + 1

    suspend fun insertReward(reward: RewardEntity) {
        rewardDao.insertReward(reward)
        markLocalSaved()
    }
    suspend fun deleteReward(reward: RewardEntity) {
        rewardDao.deleteWithTombstone(reward.id, hlcClock.now())
        markLocalSaved()
    }
    suspend fun deleteRewardById(id: String) {
        rewardDao.deleteWithTombstone(id, hlcClock.now())
        markLocalSaved()
    }
    suspend fun getAllRewardsSync(): List<RewardEntity> = rewardDao.getAllRewardsSync()

    // NOTE (Stage 3 / T3.4): event-log inserts do NOT stamp `localSavedAt`. PWA's event log lives
    // entirely in IndexedDB, written independently of `save()`'s `S.__savedAt` stamp (see
    // app.js:233-272 vs. the separate `logEvent()`/IDB path) -- an event-log-only write carries no
    // information about the synced `S`-equivalent state, so it must not count as a "local save"
    // for the stale-local guard's purposes.
    //
    // Stage 4 / T4.2 (spec §7 D6; spec §1.2 "Event object" base order `ts -> uid -> dev ->
    // [kind-specific]"; §3 addendum rule 1). PWA's `logEvent()` (app.js:644-654) always stamps
    // `ts`/`uid`/`dev` via `Object.assign` at event-CREATION time, before the object is ever
    // written to IndexedDB. This function is the single chokepoint every `EventLogEntity`
    // construction in this codebase already funnels through (completeTask/uncompleteTask/
    // scoreHabitUp/scoreHabitDown/buyReward/buyPotion/runCron below, plus MainViewModel's
    // habitReps/potion logs, plus SyncManager's diagnostic logs) -- so it is the Kotlin
    // equivalent of `logEvent()` and stamps `uid`/`dev` here, once, if not already present,
    // rather than deferring to push-time. Without this stamp, `SyncManager`'s event-push (T4.2)
    // would have nothing ever eligible to upload: addendum rule 1 (`evtUploadable`) requires
    // `e.uid` truthy and `e.dev === myDev`. `timestamp` is already always non-null (constructor
    // default `System.currentTimeMillis()`), so only `uid`/`dev` ever need backfilling here.
    suspend fun insertLog(log: EventLogEntity) {
        val stamped = if (log.uid == null) {
            val deviceId = syncDataStore.getOrCreateDeviceId()
            log.copy(
                uid = com.example.data.sync.EventSyncEngine.generateEventUid(deviceId, log.timestamp),
                dev = log.dev ?: deviceId
            )
        } else {
            log
        }
        eventLogDao.insertLog(stamped)
    }

    // WP-5.4 (spec §1.2 "Event object" base order `ts -> uid -> dev -> [kind-specific]"; spec §3
    // addendum rule 1). Thin pass-through, DELIBERATELY bypassing [insertLog]'s local-device
    // stamp-if-missing logic above. Imported backup events (`SerializationEngine.importBackup`,
    // once its WP-5.4 parsing fix populates the typed `uid`/`dev` columns from the source file)
    // carry their TRUE origin device's identity, which may legitimately differ from this
    // (importing) device's own id -- routing them through `insertLog` would silently reassign any
    // `uid == null` row (including a genuinely foreign event whose uid failed to parse, or a
    // legacy pre-uid-era event, see WP-5.4 step 3) to the LOCAL device, which is exactly the bug
    // this method exists to avoid. Callers: `SyncManager.importSnapshotFromFile`'s import-time
    // dedup+insert loop only -- never use this for a genuinely new, locally-created event.
    suspend fun insertImportedLog(log: EventLogEntity) = eventLogDao.insertLog(log)

    // Stage 4 / T4.2 (spec §3 addendum rule 1's watermark / rule 21's full-month-range refetch).
    suspend fun getLogsSinceSync(since: Long): List<EventLogEntity> = eventLogDao.getLogsSinceSync(since)
    suspend fun getLogsInRangeSync(from: Long, to: Long): List<EventLogEntity> = eventLogDao.getLogsInRangeSync(from, to)

    // Stage 4 / T4.3 (spec §3 addendum rule 18's "existingUidSet" half of the pull dedup).
    suspend fun getUidsInRangeSync(from: Long, to: Long): List<String> = eventLogDao.getUidsInRangeSync(from, to)

    suspend fun getAllTagsSync(): List<TagEntity> = tagDao.getAllTagsSync()

    // Stage 3 / T3.5: the ordinal a genuinely NEW, locally-created tag must receive so it sorts
    // AFTER every existing one -- mirroring PWA's `S.tags.push(tg)` (app.js:1912). Same caller
    // contract as `nextTaskOrderIndex()` (new-creation callers only, not merge-apply).
    suspend fun nextTagOrderIndex(): Long = (tagDao.getMaxOrderIndex() ?: 0L) + 1

    suspend fun insertTag(tag: TagEntity) {
        tagDao.insertTag(tag)
        markLocalSaved()
    }
    suspend fun deleteTagById(id: String) {
        tagDao.deleteWithTombstone(id, hlcClock.now())
        markLocalSaved()
    }

    suspend fun getAllDevicesSync(): List<DeviceEntity> = deviceDao.getAllDevicesSync()
    suspend fun insertDevice(device: DeviceEntity) {
        deviceDao.insertDevice(device)
        markLocalSaved()
    }

    suspend fun getAllCharHistorySync(): List<CharHistoryEntity> = charHistoryDao.getAllCharHistorySync()
    suspend fun insertCharHistory(entry: CharHistoryEntity) {
        charHistoryDao.insertCharHistory(entry)
        markLocalSaved()
    }

    suspend fun getAllTombstonesSync(): List<TombstoneEntity> = tombstoneDao.getAllTombstonesSync()
    suspend fun insertTombstone(tombstone: TombstoneEntity) {
        tombstoneDao.insertTombstone(tombstone)
        markLocalSaved()
    }
    suspend fun deleteTombstonesOlderThan(timestamp: Long) = tombstoneDao.deleteTombstonesOlderThan(timestamp)

    // Stage 3 / T3.1 -- snapshot-level (top-level backup `S`) extras passthrough; see
    // SnapshotExtrasEntity's class doc for scope (backup-file round-trip only).
    suspend fun getSnapshotExtrasJson(): String? = snapshotExtrasDao.getSync()?.extraJson
    suspend fun saveSnapshotExtrasJson(extraJson: String?) {
        snapshotExtrasDao.upsert(SnapshotExtrasEntity(id = 1, extraJson = extraJson))
        markLocalSaved()
    }

    // Stage 3 / T3.2 -- opaque `an.metrics` passthrough (see MetricsExtrasEntity's class doc);
    // unlike snapshotExtrasDao above, this is read/written on BOTH the backup-file path and the
    // Dropbox sync path.
    suspend fun getMetricsExtrasJson(): String? = metricsExtrasDao.getSync()?.json
    suspend fun saveMetricsExtrasJson(json: String?) {
        metricsExtrasDao.upsert(MetricsExtrasEntity(id = 1, json = json))
        markLocalSaved()
    }

    // ---- WP-8.10: clocks. The physical clock and zone are BootGate's (the one clock-ok:boot-gate-timer
    // seam, test-injectable); every updatedAt stamp is the HLC (Opti `now()`), never the wall clock.
    // WP-8.23: [physicalNow] is public: the entities have no clock default, so callers stamp
    // createdAt and event timestamps from it (the value the old defaults gave). Tombstone `at` and
    // every updatedAt are the HLC (review 49, PX-20: Opti `delMark` stamps `now()`, app.js:198).
    fun physicalNow(): Long = com.example.data.boot.BootGate.clock()
    private fun zone(): java.time.ZoneId = com.example.data.boot.BootGate.zoneProvider()

    /** WP-8.10 (PX-65): the live pause flags (Opti `S.prefs.paused / pausedDays`). */
    suspend fun pauseState(): PauseState = PauseState.of(readSyncPrefs())

    suspend fun completeTask(taskId: String, todayDow: Int = -1): Boolean {
        val task = taskDao.getTaskByIdSync(taskId) ?: return false
        val char = getCharacterSync()

        val (updatedChar, updatedTask) = RpgEngine.completeTask(
            character = char,
            task = task,
            todayDow = todayDow,
            paused = pauseState().paused,
            nowMs = physicalNow(),
            now = hlcClock.now(),
            zone = zone()
        )
        
        if (updatedTask.done && !task.done) {
            insertCharacter(updatedChar)
            insertTask(updatedTask)
            
            insertLog(EventLogEntity(
                timestamp = physicalNow(),
                kind = "complete",
                taskType = task.type,
                taskId = task.id,
                taskTitle = task.title,
                streak = updatedTask.streak,
                value = updatedTask.value
            ))
            return true
        }
        return false
    }

    suspend fun uncompleteTask(taskId: String): Boolean {
        val task = taskDao.getTaskByIdSync(taskId) ?: return false
        val char = getCharacterSync()
        
        val (updatedChar, updatedTask) = RpgEngine.uncompleteTask(char, task, pauseState().paused, hlcClock.now())
        insertCharacter(updatedChar)
        insertTask(updatedTask)
        
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "uncomplete",
            taskType = task.type,
            taskId = task.id,
            taskTitle = task.title,
            streak = updatedTask.streak,
            value = updatedTask.value
        ))
        return true
    }

    // CR-KT-022: one-shot repair of the completion history points pre-1.3 never wrote (the
    // repository logged the events but no point; see CR-KT-020). Replays every local
    // complete/uncomplete row through HistoryBackfill into the per-task history, add-only: a UTC
    // day that already carries a completed:true point is never modified, nothing is deleted, and
    // `updatedAt`/`_gr`/`completedAt`/`_histCreated`/done/streak/value are not touched -- the task
    // changes without an edit stamp, exactly what the CR's idempotent re-run fallback needs (the
    // PWA merge side is asked in the CR; a re-run after sync is always harmless).
    // Returns a [CompletionBackfillReport]; the boot hook's one-shot stamp policy reads its
    // `hadEvents` (see the type's KDoc).
    suspend fun backfillCompletionHistory(): CompletionBackfillReport {
        val events = eventLogDao.getCompletionEventsSync()
        if (events.isEmpty()) return CompletionBackfillReport(restored = 0, hadEvents = false)
        val byTask = events.groupBy { it.taskId }
        var restored = 0
        inTransaction {
            taskDao.getAllTasksSync().forEach { task ->
                val rows = byTask[task.id] ?: return@forEach
                val repaired = HistoryBackfill.repair(task, rows)
                if (repaired != task.history) {
                    restored += repaired.count { it.completed == true } - task.history.count { it.completed == true }
                    taskDao.updateTask(task.copy(history = repaired))
                }
            }
        }
        // A repaired history must reach the PWA: signal one local save (the sync debounce reads
        // this) if anything changed. No write -> no signal, so a no-op re-run stays silent.
        if (restored > 0) markLocalSaved()
        return CompletionBackfillReport(restored = restored, hadEvents = true)
    }

    /**
     * CR-KT-023: Opti `addReps(id, n)` -- the bulk "Log N reps" sheet, [n] signed. Writes the
     * history point (RpgEngine.addReps), the counters and the HLC `updatedAt` through [updateTask]
     * (one local save), then the `habitReps` event row exactly as before CR-KT-023
     * (`direction` = sign, `reps` = |n|, `value` = task value).
     *
     * Before its insert it pins the replay boundary (`MAX(id)` now), so this row -- whose point
     * this call already wrote -- is above it and [backfillRepsHistory] never replays it.
     */
    suspend fun addHabitReps(taskId: String, n: Int): TaskEntity? {
        val task = taskDao.getTaskByIdSync(taskId) ?: return null
        val atMs = physicalNow()
        val updated = RpgEngine.addReps(task, n, hlcClock.now(), atMs)
        repsReplayStore?.pinBoundary(eventLogDao.maxId() ?: 0L)
        updateTask(updated)
        insertLog(EventLogEntity(
            timestamp = atMs,
            kind = "habitReps",
            direction = if (n > 0) 1 else -1,
            taskId = task.id,
            taskTitle = task.title,
            reps = kotlin.math.abs(n),
            value = task.value
        ))
        return updated
    }

    /**
     * CR-KT-023: one-shot replay of the `habitReps` rows the pre-CR-KT-023 `addHabitReps` wrote
     * without a history point ([RepsReplay]). Only THIS device's rows (`dev` = own id, not
     * imported, not synthetic) up to the pinned boundary id: pulled rows (EventPuller skips own
     * `dev`) and imported rows came from the PWA, which already wrote the point.
     *
     * Runs at most once per install: the done flag is set BEFORE the Room write (reps add up, so a
     * crash between the write and a later flag would double count on the next run; the other
     * order can at worst lose the replay) and cleared again if the write throws. Add-only on
     * history; counters/value/updatedAt/_gr/done/streak untouched; one local save only if a task
     * changed. A re-run (Diagnostics) replays nothing.
     */
    suspend fun backfillRepsHistory(): RepsReplayReport {
        val store = repsReplayStore ?: return RepsReplayReport(0, 0)
        if (store.isReplayDone()) return RepsReplayReport(0, 0)
        val boundary = store.pinBoundary(eventLogDao.maxId() ?: 0L)
        val myDev = syncDataStore.getOrCreateDeviceId()
        val rows = eventLogDao.getHabitRepsUpToSync(boundary).filter { RepsReplay.isLocal(it, myDev, boundary) }
        store.setReplayDone(true)
        if (rows.isEmpty()) return RepsReplayReport(0, 0)
        var replayed = 0
        var tasks = 0
        try {
            val byTask = rows.groupBy { it.taskId }
            inTransaction {
                taskDao.getAllTasksSync().forEach { task ->
                    val mine = byTask[task.id] ?: return@forEach
                    val repaired = RepsReplay.replay(task, mine)
                    replayed += mine.size
                    if (repaired != task.history) {
                        tasks++
                        taskDao.updateTask(task.copy(history = repaired))
                    }
                }
            }
        } catch (e: Exception) {
            store.setReplayDone(false)
            throw e
        }
        if (tasks > 0) markLocalSaved()
        return RepsReplayReport(replayed, tasks)
    }

    suspend fun scoreHabitUp(taskId: String): Boolean {
        val task = taskDao.getTaskByIdSync(taskId) ?: return false
        val char = getCharacterSync()
        
        val atMs = physicalNow()
        val (updatedChar, updatedTask) = RpgEngine.scoreHabitUp(char, task, hlcClock.now(), atMs)
        insertCharacter(updatedChar)
        insertTask(updatedTask)

        insertLog(EventLogEntity(
            timestamp = atMs,
            kind = "habitTap",
            taskType = "habit",
            taskId = task.id,
            taskTitle = task.title,
            direction = 1,
            reps = RpgEngine.tapReps(task),
            value = updatedTask.value,
            payloadJson = if (task.difficulty.lowercase() == "log") "{\"log\":true}" else null
        ))
        return true
    }

    suspend fun scoreHabitDown(taskId: String): Boolean {
        val task = taskDao.getTaskByIdSync(taskId) ?: return false
        val char = getCharacterSync()
        
        val atMs = physicalNow()
        val (updatedChar, updatedTask) = RpgEngine.scoreHabitDown(char, task, hlcClock.now(), atMs)
        insertCharacter(updatedChar)
        insertTask(updatedTask)

        val isLog = task.difficulty.lowercase() == "log"
        insertLog(EventLogEntity(
            timestamp = atMs,
            kind = "habitTap",
            taskType = "habit",
            taskId = task.id,
            taskTitle = task.title,
            direction = -1,
            damage = if (isLog) null else RpgEngine.missDamage(task.difficulty, task.value),
            reps = if (isLog) RpgEngine.tapReps(task) else null,
            value = updatedTask.value,
            payloadJson = if (isLog) "{\"log\":true}" else null
        ))
        return true
    }

    suspend fun buyReward(rewardId: String): Boolean {
        val reward = rewardDao.getRewardByIdSync(rewardId) ?: return false
        val char = getCharacterSync()

        val updatedChar = RpgEngine.buyReward(char, reward.cost, hlcClock.now()) ?: return false
        insertCharacter(updatedChar)
        
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "purchase",
            taskTitle = reward.title,
            cost = reward.cost
        ))
        return true
    }

    /** PX-70: Opti `buyShopItem` (app.js:6472-6480); the event carries `effect: item.desc`. */
    suspend fun buyPotion(type: String): Boolean {
        val item = RpgEngine.SHOP_ITEMS.firstOrNull { it.id == type } ?: return false
        val char = getCharacterSync()
        val updatedChar = RpgEngine.buyPotion(char, type, hlcClock.now()) ?: return false
        insertCharacter(updatedChar)
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "purchase",
            taskTitle = item.title,
            cost = item.cost,
            payloadJson = kotlinx.serialization.json.buildJsonObject {
                put("effect", kotlinx.serialization.json.JsonPrimitive(item.desc))
            }.toString()
        ))
        return true
    }

    /**
     * CR-KT-016 / CR-KT-017: the task editor's Save, Opti saveTask (b7a94cd app.js:6855-6982).
     * In ONE Room transaction: reads the LIVE row and writes back only what the user changed
     * ([TaskWriteBack]: [edited] vs [base]; untouched fields, sync-arrived subtasks and the
     * attachments stay live); for a habit whose counter the user changed in the editor, runs the
     * counter loops on that row with the user's own deltas ([editedCUp]/[editedCDown] minus
     * [base]'s, Opti upDelta/downDelta). Then ONE `edit` event `{kind:'edit', taskType, taskId,
     * taskTitle, changes, counter?}` ([TaskEditEvent], [base] vs the editor's copy, never the merged
     * row: RE-PWA-017), none when nothing changed. [base] = the task when the editor opened (Opti
     * `_base`); null = the stored row. A row deleted while the editor was open is not written and
     * gets no event (Opti `idx<0`: "That task no longer exists").
     */
    suspend fun saveTaskEdit(
        base: TaskEntity?,
        edited: TaskEntity,
        attachmentDelta: com.example.data.attachments.AttachmentDelta = com.example.data.attachments.AttachmentDelta.NONE,
        editedCUp: Int? = null,
        editedCDown: Int? = null,
    ) {
        val orig = base ?: taskDao.getTaskByIdSync(edited.id) ?: return
        // The sheet copy carries the editor's counters (Opti EDIT.cUp/cDown); subtasks the user
        // changed get touchedAt from the shared HLC before the write-back (RE-PWA-018, Opti now()).
        val sheet = TaskWriteBack.stampTouched(
            orig,
            edited.copy(cUp = editedCUp ?: edited.cUp, cDown = editedCDown ?: edited.cDown),
        ) { hlcClock.now() }
        val upDelta = editedCUp?.let { it - orig.cUp } ?: 0
        val downDelta = editedCDown?.let { it - orig.cDown } ?: 0
        var counter: RpgEngine.CounterAdjustResult? = null
        val saved = inTransaction {
            val live = taskDao.getTaskByIdSync(edited.id) ?: return@inTransaction null
            var t = TaskWriteBack.apply(orig, sheet, live)
            if (t.type == "habit" && (upDelta != 0 || downDelta != 0)) {
                val (c, adjusted, r) = RpgEngine.adjustHabitCounters(
                    getCharacterSync(), t, t.cUp, t.cDown,
                    hlcClock.now(), upDelta = upDelta, downDelta = downDelta,
                )
                characterDao.insertCharacter(c)
                t = adjusted
                counter = r
            }
            taskDao.updateTask(t)
            t
        } ?: return
        markLocalSaved()
        val changes = com.example.data.activity.TaskEditEvent.changes(orig, edited, attachmentDelta, counterChanged = counter != null)
        val payload = com.example.data.activity.TaskEditEvent.payload(changes, counter) ?: return
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "edit",
            taskType = saved.type,
            taskId = saved.id,
            taskTitle = saved.title,
            payloadJson = payload
        ))
    }

    /** CR-KT-016: Opti saveTask new task, `logEvent({kind:'create', taskType, taskId, taskTitle})`. */
    suspend fun logTaskCreate(task: TaskEntity) {
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "create",
            taskType = task.type,
            taskId = task.id,
            taskTitle = task.title,
        ))
    }

    /**
     * CR-PWA-003: the task editor's `edit` event for an image add/remove,
     * `{kind:'edit', taskType, taskId, taskTitle, changes:[{field:'attachments',added,removed}]}`
     * (Opti saveTask writes the event when `_ch` holds only this item too). Nothing for an empty delta.
     */
    suspend fun logAttachmentEdit(task: TaskEntity, delta: com.example.data.attachments.AttachmentDelta) {
        val item = com.example.data.attachments.AttachmentActivity.changeItem(delta) ?: return
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "edit",
            taskType = task.type,
            taskId = task.id,
            taskTitle = task.title,
            payloadJson = com.example.data.attachments.AttachmentActivity.editPayload(listOf(item))
        ))
    }

    // ---- WP-8.6: merge-apply row writes (Opti syncApply replaces S.tasks etc. wholesale) ----
    // A row absent from the merged state is removed WITHOUT a new tombstone: the merged
    // `deletions` list is written as is (Opti `S.deletions = subset.deletions`).

    suspend fun getTaskByIdSync(id: String): TaskEntity? = taskDao.getTaskByIdSync(id)
    /** CR-KT-015: one task as a Flow (null when it does not exist). */
    fun observeTask(id: String): Flow<TaskEntity?> = taskDao.getTaskById(id)
    suspend fun deleteTaskRowOnly(id: String) = taskDao.deleteTaskByIdInternal(id)
    suspend fun deleteRewardRowOnly(id: String) = rewardDao.deleteRewardByIdInternal(id)
    suspend fun deleteTagRowOnly(id: String) = tagDao.deleteTagByIdInternal(id)
    suspend fun deleteTombstoneRowOnly(id: String) = tombstoneDao.deleteTombstoneById(id)
    suspend fun deleteDeviceRowOnly(id: String) = deviceDao.deleteDeviceById(id)
    suspend fun deleteCharHistoryRowOnly(date: Long) = charHistoryDao.deleteCharHistoryByDate(date)

    /**
     * The `sync_prefs` row (Opti `S.prefs.paused/pausedDays/pausedAt`), through [SyncPrefsDao]
     * (WP-8.10; replaces WP-8.6's raw SQL). null without a database or without a row.
     */
    suspend fun readSyncPrefs(): SyncPrefsEntity? = database?.syncPrefsDao()?.get()

    /** Upserts the `sync_prefs` row (see [readSyncPrefs]). No-op without a database. */
    suspend fun writeSyncPrefs(p: SyncPrefsEntity) {
        database?.syncPrefsDao()?.upsert(p.copy(id = 0))
    }

    /**
     * WP-8.10 (PX-65): port of Opti `setPause(n)` (app.js:6696): `paused = !!n`, `pausedAt = now()`
     * (HLC), `pausedDays = []` when pausing with no array yet; then `save()`. Un-pausing KEEPS the
     * row and its `pausedDays` (Opti keeps the keys). The Settings toggle is WP-8.14's.
     */
    suspend fun setPause(paused: Boolean, hlcNow: Long = hlcClock.now()) {
        if (database == null) return
        inTransaction {
            val cur = readSyncPrefs()
            val days = cur?.pausedDays ?: if (paused) emptyList() else null
            writeSyncPrefs(SyncPrefsEntity(id = 0, paused = paused, pausedDays = days, pausedAt = hlcNow, extraJson = cur?.extraJson))
        }
        markLocalSaved()
    }

    /**
     * WP-8.6 (PX-48, SY-54): port of Opti's device-name setter (app.js:6700-6722): register or
     * rename THIS device's `devices` entry, stamped with [hlcNow] (Opti `d.updatedAt = now()`).
     * A no-op when the name does not change; clearing a name that was never set creates nothing.
     * Returns true when it wrote. The Settings row is WP-8.14's.
     */
    suspend fun setDeviceName(name: String, hlcNow: Long = hlcClock.now()): Boolean {
        val devId = syncDataStore.getOrCreateDeviceId()
        val trimmed = name.trim()
        val existing = deviceDao.getAllDevicesSync().firstOrNull { it.id == devId }
        val prevName = existing?.name ?: ""
        if (prevName == trimmed) return false
        if (existing == null && trimmed.isEmpty()) return false
        val base = existing ?: DeviceEntity(id = devId, name = "", updatedAt = 0L)
        insertDevice(base.copy(name = trimmed, updatedAt = hlcNow))
        insertLog(EventLogEntity(
            timestamp = physicalNow(),
            kind = "devicename",
            taskTitle = "Device name",
            payloadJson = kotlinx.serialization.json.buildJsonObject {
                put("notes", kotlinx.serialization.json.JsonPrimitive(
                    "Device " + devId.take(6) + " → \"" + (trimmed.ifEmpty { "(cleared)" }) + "\"" +
                        (if (prevName.isNotEmpty()) " (was \"$prevName\")" else "")))
                put("deviceId", kotlinx.serialization.json.JsonPrimitive(devId))
                put("deviceName", kotlinx.serialization.json.JsonPrimitive(trimmed))
                put("prevDeviceName", kotlinx.serialization.json.JsonPrimitive(prevName))
            }.toString()
        ))
        return true
    }

    internal suspend fun deviceIdForTaps(): String = syncDataStore.getOrCreateDeviceId()

    /** WP-8.6: habit tap scoring with the inbox/tap metadata (Opti scoreHabit meta.evt, app.js:2464). */
    internal suspend fun scoreHabitTapped(task: TaskEntity, dir: Int, uid: String, ts: Long, src: String, deviceId: String): TaskEntity {
        val char = getCharacterSync()
        // Opti meta.atMs = the real tap ts (app.js:2468): the history point is dated [ts].
        val hlcNow = hlcClock.now()
        val (updatedChar, updatedTask) = if (dir > 0) RpgEngine.scoreHabitUp(char, task, hlcNow, ts) else RpgEngine.scoreHabitDown(char, task, hlcNow, ts)
        insertCharacter(updatedChar)
        insertTask(updatedTask)
        val isLog = task.difficulty.lowercase() == "log"
        val dmg = if (dir < 0 && !isLog) RpgEngine.missDamage(task.difficulty, task.value) else null
        insertLog(EventLogEntity(
            timestamp = ts,
            kind = "habitTap",
            taskType = "habit",
            taskId = task.id,
            taskTitle = task.title,
            direction = dir,
            damage = dmg,
            reps = if (dir > 0 || isLog) RpgEngine.tapReps(task) else null,
            value = updatedTask.value,
            uid = uid,
            dev = deviceId,
            payloadJson = kotlinx.serialization.json.buildJsonObject {
                if (isLog) put("log", kotlinx.serialization.json.JsonPrimitive(true))
                put("src", kotlinx.serialization.json.JsonPrimitive(src))
            }.toString()
        ))
        return updatedTask
    }

    // ================================================================ WP-8.10: cron, startDay, RYA

    /**
     * §5.0 B11 (r13, minor 5): the RYA opener of the bound MainViewModel (its `openRyaLocked`),
     * or null in a process without UI. A MEMBER, so it can be @Volatile.
     */
    @Volatile var ryaOpener: (suspend (nowMs: Long) -> Unit)? = null

    /** Test seams (RyaConfirmActionTest 9, 12, 13; DayMutexRaceTest 2, 3). Null in production. */
    @Volatile internal var testHookBeforeCredit: (suspend (taskId: String) -> Unit)? = null
    @Volatile internal var testHookInCron: (suspend () -> Unit)? = null

    /** §5.0 B10: ONE read of the committed cron inputs (a DAO transaction; separate reads without a database). */
    private suspend fun cronInputsOnce(): CronInputs =
        database?.cronDao()?.cronInputsOnce()
            ?: CronInputs(characterDao.getCharacterSync(), taskDao.getAllTasksSync(), null)

    private fun lastCronOf(c: CharacterEntity?): DayStamp = c?.lastCron?.trim()?.toIntOrNull() ?: 0

    /** §5.0 B12 (r13, minor 1): the live row, or null when it is missing OR tombstoned. */
    suspend fun taskOnce(id: String): TaskEntity? {
        val t = taskDao.getTaskByIdSync(id) ?: return null
        if (tombstoneDao.getTombstoneById(id) != null) return null
        return t
    }

    /**
     * §5.0 B12 / app.js:2667-2691: credit one ticked daily for yesterday, judged at [nowMs] (the
     * commit-time clock). ONE transaction that re-reads the row (r14, T-9): false when it is now
     * missing or tombstoned, done, or a daily not due yesterday. True = credited and committed.
     */
    suspend fun creditYesterday(taskId: String, nowMs: Long): Boolean {
        testHookBeforeCredit?.invoke(taskId)
        val ok = inTransaction {
            val live = taskOnce(taskId) ?: return@inTransaction false
            val char = characterDao.getCharacterSync() ?: CharacterEntity(updatedAt = hlcClock.now())
            val out = RpgEngine.creditYesterday(char, live, pauseState().paused, nowMs, hlcClock.now(), zone())
                ?: return@inTransaction false
            characterDao.insertCharacter(out.character)
            taskDao.insertTask(out.task)
            insertLog(out.log)
            true
        }
        if (ok) markLocalSaved()
        return ok
    }

    /**
     * §5.0 B10 (r13, T-1, T-6): Opti `runCron()` (app.js:2911-2981) as ONE transaction: read
     * `lastCron`, the ordering guard `dayStamp(nowMs) <= lastCron → false` (:2922), then the
     * engine and every write (character, changed tasks, pause days, miss logs, char snapshot, the
     * cron log, `lastCron`). Never reads the clock. Opti `save()` → localSavedAt once.
     */
    suspend fun runCron(nowMs: Long): Boolean {
        val z = zone()
        val ran = inTransaction {
            val inputs = cronInputsOnce()
            val char = inputs.character ?: CharacterEntity(updatedAt = hlcClock.now())
            val lastCron = lastCronOf(inputs.character)
            val today = LocalDay.dayStamp(nowMs, z)
            if (today <= lastCron) return@inTransaction false
            val pause = PauseState.of(inputs.prefs)
            val out = RpgEngine.runCron(RpgEngine.CronInput(char, inputs.tasks, lastCron, nowMs, z, hlcClock.now()), today, pause)
            if (!out.ran) return@inTransaction false
            testHookInCron?.invoke()
            characterDao.insertCharacter(out.character)
            val before = inputs.tasks.associateBy { it.id }
            out.tasks.forEach { if (before[it.id] != it) taskDao.insertTask(it) }
            if (pause.paused && inputs.prefs != null && out.pause.pausedDays != inputs.prefs.pausedDays) {
                writeSyncPrefs(inputs.prefs.copy(pausedDays = out.pause.pausedDays))
            }
            out.missLogs.forEach { insertLog(it) }
            out.charSnapshot?.let { charHistoryDao.insertCharHistory(it) }
            val yStamp = LocalDay.dayStamp(LocalDay.localDayDateAtOffset(nowMs, -1, z), z)
            val yDow = (LocalDay.getDay(nowMs, z) + 6) % 7
            insertLog(EventLogEntity(
                timestamp = nowMs,
                kind = "cron",
                payloadJson = "{\"yesterdayDow\":$yDow,\"yesterdayStamp\":\"$yStamp\"}"
            ))
            true
        }
        if (ran) markLocalSaved()
        return ran
    }

    /**
     * §5.0 B10 / app.js:2893-2898 `startDay()`: one read, `missed = missedYesterdayDailies(...)`;
     * nothing missed → [runCron] with the same [nowMs]. Returns `missed` (the frozen RYA list) in
     * both branches. Never waits for the user. The caller holds `BootGate.dayMutex`.
     */
    suspend fun startDay(nowMs: Long): List<TaskEntity> {
        val inputs = cronInputsOnce()
        val missed = RpgEngine.missedYesterdayDailies(inputs.tasks, PauseState.of(inputs.prefs), lastCronOf(inputs.character), nowMs, zone())
        if (missed.isEmpty()) runCron(nowMs)
        return missed
    }

    /**
     * §5.0 W1 step 3: the body 8.I binds with `BootGate.bindStartDay(repository.startDayBinding())`
     * on the shared QuestaApplication repository. Runs inside B3's `dayMutex.withLock`.
     */
    fun startDayBinding(): suspend () -> Unit = {
        val nowMs = com.example.data.boot.BootGate.clock()
        val opener = ryaOpener
        if (opener != null) opener(nowMs) else startDay(nowMs)
    }
}
