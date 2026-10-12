package com.example.ui

import com.example.data.local.entities.TagEntity
import java.util.UUID

/**
 * CR-KT-008 (KT-95): `task.tags` WRITES hold tag IDS, as the PWA does (Opti `addTagToEdit` /
 * `toggleEditTag` push ids, app.js:3299-3303; `tagById` app.js:3256 and `tagChips` app.js:3272
 * resolve ids only). Reads stay tolerant of legacy name tokens ([TagFilter.kt],
 * `CardParts.resolveTags`): old Kotlin data holds names until the task is next saved.
 */

/**
 * The id a new tag gets. Same generator [MainViewModel.createTag] always used (UUID); it is
 * produced in the UI layer so the editor knows the id before the task is saved.
 */
fun newTagId(): String = UUID.randomUUID().toString()

/** Whether [tokens] refer to [tag], by id (PWA) or by legacy name (pre-fix Kotlin). */
fun holdsTag(tokens: List<String>, tag: TagEntity): Boolean = tag.id in tokens || tag.name in tokens

/**
 * The editor-save form of a task's tags: a token naming a known tag becomes that tag's id, an id
 * stays, an unknown token is kept untouched. A tag held twice (id and name) is kept once, at its
 * first position. Only called on a save the user made -- never as a mass rewrite.
 */
fun tagTokensToIds(tokens: List<String>, tags: List<TagEntity>): List<String> {
    val ids = tags.map { it.id }.toSet()
    val idByName = tagIdIndex(tags)
    val seen = HashSet<String>()
    val out = ArrayList<String>(tokens.size)
    for (token in tokens) {
        val id = if (token in ids) token else idByName[token]
        if (id == null) out += token else if (seen.add(id)) out += id
    }
    return out
}

/** Editor chip toggle: deselect removes both spellings; select adds the id (Opti toggleEditTag). */
fun toggleEditorTag(tokens: List<String>, tag: TagEntity): List<String> =
    if (holdsTag(tokens, tag)) removeEditorTag(tokens, tag) else tokens + tag.id

/** Removes [tag] from [tokens] in either spelling. */
fun removeEditorTag(tokens: List<String>, tag: TagEntity): List<String> =
    tokens.filter { it != tag.id && it != tag.name }

/**
 * Editor "Add Tag" (Opti addTagToEdit, app.js:3299-3301): an existing tag ([existing], matched by
 * name ignoring case) attaches its id; otherwise [createdId] -- the id the new tag row is created
 * with -- is attached. Never adds a tag the task already holds in either spelling.
 */
fun attachEditorTag(tokens: List<String>, existing: TagEntity?, createdId: String): List<String> =
    when {
        existing != null -> if (holdsTag(tokens, existing)) tokens else tokens + existing.id
        createdId in tokens -> tokens
        else -> tokens + createdId
    }
