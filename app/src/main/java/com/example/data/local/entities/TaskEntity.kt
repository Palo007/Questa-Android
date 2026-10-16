package com.example.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.attachments.Attachment
import com.example.data.clock.DayStamp
import kotlinx.serialization.Serializable

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val touchedAt: Long? = null,
    // Stage 3 / T3.1 (unknown-field passthrough, spec sync_format_spec.md §1.2 "Checklist item
    // object"): compact-JSON verbatim copy of the ORIGINAL checklist-item object as parsed from a
    // PWA import/merge (including this item's own `id`/`text`/`done`/`touchedAt` at their
    // original values, in their original key order, plus any field this data class doesn't model).
    // Null for items created fresh in the Kotlin app (no PWA original to preserve). See
    // SerializationEngine.overlayJson/buildChecklistItemJson/parseChecklistItem for how this is
    // used: on export, this item's current field values are overlaid onto this stored object IN
    // PLACE (preserving original key order and any unmodeled keys); on import/merge, this is
    // re-captured from the winning/parsed object so extras keep traveling forward.
    val extraJson: String? = null
)

@Serializable
data class GrantedReward(
    val xp: Int,
    val gold: Double,
    val mp: Int,
    val delta: Double,
    // Stage 3 / T3.1 opus-review FIX 3 (unknown-field passthrough gap #3 from the T3.1 review):
    // compact-JSON verbatim copy of the ORIGINAL granted-reward object as parsed (task-level `gr`
    // or a `history[]` entry's `reward`), so any field this data class doesn't model survives
    // export the same way task/reward/tag/etc. extras do. Null for a reward computed fresh in the
    // Kotlin app (no PWA original to preserve). See SerializationEngine.buildGrantedRewardJson /
    // parseGrantedReward.
    val extraJson: String? = null
)

@Serializable
data class TaskHistoryPoint(
    val date: Long,
    val value: Double? = null,
    val scoredUp: Int? = null,
    val scoredDown: Int? = null,
    val completed: Boolean? = null,
    val checklist: List<ChecklistItem>? = null,
    val reward: GrantedReward? = null,
    val reps: Int? = null,
    val repCounted: Boolean? = null,
    val scored: Boolean? = null,
    val isDue: Boolean? = null,
    val repeat: List<Boolean>? = null,
    // Stage 3 / T3.1 opus-review FIX 3: compact-JSON verbatim copy of the ORIGINAL task `history[]`
    // entry as parsed, so an unmodeled field on a history point (a class of gap the original T3.1
    // pass explicitly scoped out -- see stage log "Open risks" #3) round-trips like every other
    // per-record extra. Null for a history point recorded fresh in the Kotlin app. See
    // SerializationEngine.buildTaskHistoryPointJson / parseTaskHistoryPoint.
    val extraJson: String? = null
)

@Entity(tableName = "tasks")
@Serializable
data class TaskEntity(
    @PrimaryKey val id: String,
    val type: String, // "habit", "daily", "todo"
    val title: String,
    val notes: String = "",
    val difficulty: String = "easy", // "trivial", "easy", "medium", "hard"
    val value: Double = 0.0,
    val done: Boolean = false,
    // WP-8.2 (schema v7, D9): was `Int = 0`. Made nullable so "no streak known" (a v6 row whose
    // `extraJson` never carried a streak) round-trips as null rather than the ambiguous 0, per the
    // §5 card's "(+) null = absent" rule and Migration6to7Test's "streak null exactly where it is
    // absent" acceptance criterion.
    val streak: Int? = 0,
    val repeat: List<Boolean>? = null, // repeat array of 7 elements (Sun=0..Sat=6)
    val checklist: List<ChecklistItem> = emptyList(),
    val tags: List<String> = emptyList(),
    val history: List<TaskHistoryPoint> = emptyList(),
    val createdAt: Long, // WP-8.23: no clock default (clock baseline 0); the caller passes the stamp
    val updatedAt: Long,
    val doneAt: Long? = null,
    // WP-8.2 (schema v7, D9) -- new typed columns backfilled from `extraJson` by MIGRATION_6_7.
    // null = absent (the PWA original never carried this field / this task predates the feature).
    val up: Boolean? = null,
    val down: Boolean? = null,
    val quickLog: QuickLogMode? = null,
    val reminders: List<ReminderSpec>? = null,
    val completedAt: Long? = null,
    val doneDay: DayStamp? = null,
    val cResetOn: DayStamp? = null,
    // review r1 blocker 4: `t.cAbs[deviceId] = {ua, cUp, cDown}` (sync.js:1859-1872,
    // `_cAbsEntry`) -- see TaskAbsEntry's KDoc. null = absent.
    val cAbs: Map<String, TaskAbsEntry>? = null,
    val missedOn: Int? = null, // dayStamp of previous cron, e.g. 20260716
    val cUp: Int = 0,
    val cDown: Int = 0,
    val resetFreq: String? = null,
    val repsPerTap: Int? = null,
    val gr: GrantedReward? = null,
    // CR-KT-020 (schema v9): the daily-only wire key `_histCreated` (app.js:2513-2515). Written by
    // `RpgEngine.completeTask` when a daily completes: true = the completion PUSHED a new
    // `history` point, false = it merged into an existing same-UTC-day one. `unlogToday` may only
    // pop a point this completion actually created (app.js:2536-2553), so the flag has to survive
    // a process restart to be read there. NULL = absent: a todo is never stamped, and the un-tick
    // deletes it whenever the history was non-empty (app.js:2552-2553; no day test since
    // CR-PWA-004 -- see RpgEngine.unlogToday).
    val histCreated: Boolean? = null,
    // Stage 3 / T3.1 (unknown-field passthrough): compact-JSON verbatim copy of the ORIGINAL task
    // object as parsed from a PWA import/merge -- e.g. `reminders[]` (spec §1.2 "task" table,
    // added by saveTask()/line 4439), `up`/`down` (habit tap-enable flags), or any future PWA
    // field this entity doesn't model -- in their original relative key position. Null for tasks
    // created fresh in the Kotlin app. See SerializationEngine.buildTaskJson/parseTaskEntity.
    val extraJson: String? = null,
    // Stage 3 / T3.5 (D12, opus-review finding #3 on T3.1): a persisted, Room-only ordinal
    // recording this task's POSITION within the array Kotlin last received or synthesized it in
    // -- NOT a PWA field, never emitted on the wire (SerializationEngine.buildTaskJson does not
    // reference it). Exists because `TaskDao` previously queried `ORDER BY createdAt DESC` with no
    // memory of "the array position this record actually held", so a re-export's `tasks[]` order
    // could differ from the order Kotlin last received it in even after T3.1 made each element's
    // own key order/fields round-trip losslessly (see stage_3 log, T3.1 opus review "Open risk
    // #2"/review finding #3). Two writers:
    //  1. `SyncManager.applyMergedSnapshotToDatabase` stamps this = the record's index within the
    //     already-correctly-ordered merged/imported array (both `mergeCollection`'s "preserve
    //     local order, then remote-only" algorithm -- verified byte-for-byte against PWA's
    //     `sync.js:795-799` -- and `importBackup`'s plain array walk already produce the right
    //     order; this field is what lets Kotlin REMEMBER that order across a later re-export
    //     instead of re-deriving a fresh, possibly-wrong one from `createdAt`).
    //  2. `RpgRepository.nextTaskOrderIndex()` (used by `MainViewModel.createTask`) assigns a new
    //     LOCALLY-created task an ordinal smaller than every existing one, mirroring PWA's
    //     `S.tasks.unshift(EDIT)` (app.js:4545) -- a brand new task is prepended, not appended.
    // `TaskDao`'s queries now `ORDER BY orderIndex ASC` (see TaskDao.kt).
    val orderIndex: Long = 0L,
    // CR-KT-015 (schema v8): images on the task, wire key `attachments`. Empty = no key on the
    // wire. JSON TEXT column via Converters (AttachmentJson); merged by OptiSnapshotMerger's
    // attachments splice, never last-writer-wins.
    @ColumnInfo(defaultValue = "'[]'")
    val attachments: List<Attachment> = emptyList()
)
