package com.example.ui

import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity

/**
 * Tag identity for the dashboard filter.
 *
 * `task.tags` is not a single vocabulary: Opti/PWA rows store the tag's **id** (PWA `addTag` /
 * sync.js), while the pre-CR-KT-008 Kotlin edit sheet stored the tag's **name** (Kotlin now writes
 * ids, see [TagTokens.kt]; old name tokens remain until the task is next saved).
 * [CardParts.resolveTags] already accepts either spelling for the
 * per-card chips; this file gives the filter row and the filter the same tolerance.
 *
 * Without it a PWA-origin export produced two chips for one tag -- the name (matching nothing)
 * and the raw id (matching everything) -- see `TagFilterTest`.
 */

/** id -> name for every known tag. Tokens that are not ids fall through unchanged. */
fun tagNameIndex(tags: List<TagEntity>): Map<String, String> = tags.associate { it.id to it.name }

/**
 * The tag name a `task.tags` entry refers to: an id resolves through [nameById], anything else
 * (a name, or a token whose tag row no longer exists) is returned as-is.
 */
fun canonicalTagName(token: String, nameById: Map<String, String>): String =
    nameById[token] ?: token

/** name -> id for every known tag. Tokens that are not names fall through unchanged. */
fun tagIdIndex(tags: List<TagEntity>): Map<String, String> = tags.associate { it.name to it.id }

/**
 * The tag id a `task.tags` entry refers to: a name resolves through [idByName], an id is
 * already canonical, and anything else (a token whose tag row no longer exists) is returned
 * as-is so it stays filterable. The analytics boundary speaks ids (`AnView.tags`, `tagRows`,
 * the golden fixture), so this is the direction that keeps one tag = one bucket key.
 */
fun canonicalTagId(token: String, idByName: Map<String, String>): String =
    idByName[token] ?: token

/**
 * The filter bar's chips: every raw task token canonicalised to its tag name, plus every tag name
 * (so a tag with no tasks still has a chip), de-duplicated, blank-free and sorted.
 */
fun tagFilterLabels(rawTaskTags: List<String>, tags: List<TagEntity>): List<String> {
    val nameById = tagNameIndex(tags)
    return (rawTaskTags.map { canonicalTagName(it, nameById) } + tags.map { it.name })
        .distinct()
        .filter { it.isNotEmpty() }
        .sorted()
}

/**
 * Whether a task passes the selected filter chip. [selected] is the chip label (a tag name, a raw
 * token for a deleted tag, `null`/blank for "no filter", or `"None"` for "untagged"), matching
 * `MainViewModel.allTasks`'s previous behaviour token-for-token -- only the comparison side now
 * canonicalises `task.tags` first.
 */
fun taskMatchesTag(task: TaskEntity, selected: String?, nameById: Map<String, String>): Boolean =
    when {
        selected.isNullOrBlank() -> true
        selected == "None" -> task.tags.isEmpty()
        else -> task.notes.contains("#$selected", ignoreCase = true) ||
            task.tags.any { canonicalTagName(it, nameById).equals(selected, ignoreCase = true) }
    }
