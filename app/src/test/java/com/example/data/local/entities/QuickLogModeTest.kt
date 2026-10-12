package com.example.data.local.entities

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WP-8.2 (Wave 1) -- §5 card `QuickLogModeTest`: the [QuickLogMode] enum round-trips through both
 * kotlinx.serialization (used by [ReminderSpec]'s sibling wire shapes) and the Room
 * [com.example.data.local.Converters] string mapping MIGRATION_6_7 and `TaskEntity.quickLog` rely
 * on.
 */
class QuickLogModeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `all three values round-trip through kotlinx serialization`() {
        for (mode in QuickLogMode.entries) {
            val encoded = json.encodeToString(mode)
            val decoded = json.decodeFromString<QuickLogMode>(encoded)
            assertEquals(mode, decoded)
        }
    }

    @Test
    fun `Converters maps QuickLogMode to and from its enum name`() {
        val converters = com.example.data.local.Converters()
        for (mode in QuickLogMode.entries) {
            val asString = converters.fromQuickLogMode(mode)
            assertEquals(mode.name, asString)
            assertEquals(mode, converters.toQuickLogMode(asString))
        }
    }

    @Test
    fun `Converters maps null and unknown strings to null`() {
        val converters = com.example.data.local.Converters()
        assertNull(converters.fromQuickLogMode(null))
        assertNull(converters.toQuickLogMode(null))
        assertNull(converters.toQuickLogMode(""))
        assertNull(converters.toQuickLogMode("NOT_A_MODE"))
    }
}
