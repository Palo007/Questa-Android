package com.example.ui

import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the dashboard's tag filter chips (export `questa-partial-1790487633184.json`).
 *
 * `task.tags` carries a tag **id** on Opti/PWA-origin rows and a tag **name** on rows the Kotlin
 * editor wrote. The chip bar concatenated the raw tokens with the tag names, so a PWA row yielded
 * a duplicate raw-id chip: `Old` matched 0 tasks while `msyepixjvzfh8` matched 21 -- one tag,
 * two chips, one of them dead. Same divergence broke `selectedTag`, which compared the chip label
 * against the raw tokens.
 *
 * `CardParts.resolveTags` already tolerates both spellings for the card chips; these functions are
 * the same tolerance for the filter row and the filter itself.
 */
class TagFilterTest {

    private val oldTag = TagEntity(
        id = "msyepixjvzfh8",
        name = "Old",
        color = "#50b5e9",
        createdAt = 1L,
        updatedAt = 1L,
    )
    private val topTag = TagEntity(
        id = "mrn5jp2py482q",
        name = "TOP",
        color = "#123456",
        createdAt = 2L,
        updatedAt = 2L,
    )
    private val tags = listOf(topTag, oldTag)
    private val nameById = tagNameIndex(tags)

    private fun task(tagTokens: List<String> = emptyList(), notes: String = "") = TaskEntity(
        id = "t1",
        type = "habit",
        title = "Habit",
        notes = notes,
        tags = tagTokens,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ---- index ---------------------------------------------------------------------------

    @Test
    fun index_mapsIdToName() {
        assertEquals("Old", nameById["msyepixjvzfh8"])
        assertEquals("TOP", nameById["mrn5jp2py482q"])
    }

    @Test
    fun index_keepsUntrackedTokensAsThemselves() {
        assertEquals("ghost", canonicalTagName("ghost", nameById))
        assertEquals("Old", canonicalTagName("Old", nameById))
    }

    // ---- chip bar ------------------------------------------------------------------------

    @Test
    fun pwaIdToken_isReplacedByItsTagName() {
        assertEquals(listOf("Old", "TOP"), tagFilterLabels(listOf("msyepixjvzfh8"), tags))
    }

    @Test
    fun kotlinNameToken_isNotDuplicated() {
        assertEquals(listOf("Old", "TOP"), tagFilterLabels(listOf("Old"), tags))
    }

    @Test
    fun mixedIdAndNameTokens_collapseToOneChip() {
        // the reported symptom: id + name of the SAME tag must not both survive distinct()
        assertEquals(
            listOf("Old", "TOP"),
            tagFilterLabels(listOf("msyepixjvzfh8", "Old", "mrn5jp2py482q"), tags),
        )
    }

    @Test
    fun tagWithNoTasks_keepsItsChip() {
        assertEquals(listOf("Old", "TOP"), tagFilterLabels(emptyList(), tags))
    }

    @Test
    fun unknownToken_passesThroughSoItIsStillFilterable() {
        // a token whose tag row was deleted must not vanish from the bar
        assertEquals(listOf("Old", "TOP", "ghost"), tagFilterLabels(listOf("ghost"), tags))
    }

    @Test
    fun emptyTokens_areDroppedAndResultIsSorted() {
        assertEquals(listOf("Old", "TOP"), tagFilterLabels(listOf("", "TOP", ""), tags))
    }

    // ---- filter --------------------------------------------------------------------------

    @Test
    fun filter_matchesPwaIdTokenByName() {
        assertTrue(taskMatchesTag(task(tagTokens = listOf("msyepixjvzfh8")), "Old", nameById))
    }

    @Test
    fun filter_matchesKotlinNameToken() {
        assertTrue(taskMatchesTag(task(tagTokens = listOf("Old")), "Old", nameById))
    }

    @Test
    fun filter_isCaseInsensitiveOnBothSides() {
        assertTrue(taskMatchesTag(task(tagTokens = listOf("msyepixjvzfh8")), "old", nameById))
        assertTrue(taskMatchesTag(task(tagTokens = listOf("OLD")), "Old", nameById))
    }

    @Test
    fun filter_notesHashTagStillMatches() {
        assertTrue(taskMatchesTag(task(notes = "read #Old books"), "Old", nameById))
        assertTrue(taskMatchesTag(task(notes = "read #OLD books"), "old", nameById))
    }

    @Test
    fun filter_otherTagDoesNotMatch() {
        assertTrue(taskMatchesTag(task(tagTokens = listOf("mrn5jp2py482q")), "TOP", nameById))
        assertFalse(taskMatchesTag(task(tagTokens = listOf("mrn5jp2py482q")), "Old", nameById))
    }

    @Test
    fun filter_untaggedTaskNeverMatchesATagChip() {
        assertFalse(taskMatchesTag(task(), "Old", nameById))
    }

    @Test
    fun filter_noneMeansUntagged() {
        assertTrue(taskMatchesTag(task(), "None", nameById))
        assertFalse(taskMatchesTag(task(tagTokens = listOf("Old")), "None", nameById))
    }

    @Test
    fun filter_noOrBlankSelectionPassesEverything() {
        assertTrue(taskMatchesTag(task(), null, nameById))
        assertTrue(taskMatchesTag(task(tagTokens = listOf("Old")), "", nameById))
        assertTrue(taskMatchesTag(task(tagTokens = listOf("Old")), "   ", nameById))
    }

    @Test
    fun filter_deletedTagTokenMatchesItself() {
        // chip bar falls back to the raw token when the tag row is gone: it must still filter
        assertTrue(taskMatchesTag(task(tagTokens = listOf("gone-id")), "gone-id", nameById))
    }
}
