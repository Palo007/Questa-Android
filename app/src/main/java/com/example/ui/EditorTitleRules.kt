package com.example.ui

import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.merge.Js
import java.util.Locale

/**
 * WP-8.22-A: what Opti stores when an editor's name field is blank (app.js @7d5d4f3).
 * All trims are JS `String.prototype.trim`. Metric and view names stay "Name required"
 * (app.js:4073, 4096, 4364), handled in AnalyticsViewModel.
 */
object EditorTitleRules {
    /** saveTask, app.js:6049: `eTitle.value.trim()||'Untitled'` (new and edit alike). */
    fun task(raw: String): String = Js.jsTrim(raw).ifEmpty { "Untitled" }

    /** saveReward, app.js:6431: `rTitle.value.trim()||'Reward'` (new and edit alike). */
    fun reward(raw: String): String = Js.jsTrim(raw).ifEmpty { "Reward" }

    /** saveReward, app.js:6432: `Math.max(0, parseFloat(rCost.value)||0)`; `||0` also maps NaN and -0 to 0. */
    fun rewardCost(raw: String): Double {
        val v = Js.parseFloat(raw)
        return if (v.isNaN() || v == 0.0) 0.0 else maxOf(0.0, v)
    }

    /** saveTask, app.js:6057: drop rows whose text trims empty; kept rows keep their text as typed. */
    fun checklist(items: List<ChecklistItem>): List<ChecklistItem> =
        items.filter { Js.jsTrim(it.text).isNotEmpty() }

    /** addTag, app.js:3258: `name.trim()`, and an empty name makes no tag (null). */
    fun tagName(raw: String): String? = Js.jsTrim(raw).ifEmpty { null }

    /** addTag, app.js:3259: `t.name.toLowerCase()===name.toLowerCase()` reuses that tag. */
    fun existingTag(name: String, tags: List<TagEntity>): TagEntity? {
        val key = name.lowercase(Locale.ROOT)
        return tags.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
    }

    /** Opti openEdit new-task template (b7a94cd app.js:6273): every new task has `repeat:[true x7]`. */
    val NEW_TASK_REPEAT: List<Boolean> = List(7) { true }

    /**
     * CR-KT-017: the `repeat` the editor saves. The day grid is shown for a daily only and Opti never
     * clears `repeat`: a daily (and any new task, Opti's template) saves the grid; any other saved task
     * keeps its stored `repeat`, absent included. The type cannot change in the sheet.
     */
    fun repeat(type: String, stored: TaskEntity?, sheetDays: List<Boolean>): List<Boolean>? =
        if (type == "daily" || stored == null) sheetDays else stored.repeat

    /**
     * CR-KT-017: the `resetFreq` an edit saves. The picker is shown for a habit only and Opti never
     * clears `resetFreq` (its new-task template gives every type `'daily'`): any other type keeps the stored value.
     */
    fun resetFreq(type: String, stored: TaskEntity, sheetFreq: String): String? =
        if (type == "habit") sheetFreq else stored.resetFreq
}
