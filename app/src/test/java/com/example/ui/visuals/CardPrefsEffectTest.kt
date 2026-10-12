package com.example.ui.visuals

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.CardPrefs
import com.example.ui.DailyCardItem
import com.example.ui.HabitCardItem
import com.example.ui.LocalCardPrefs
import com.example.ui.TodoCardItem
import com.example.ui.freshMainDispatcher
import com.example.ui.gestures.CardTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WP-8.18 (PX-88): the cards consume [LocalCardPrefs]: card thickness (min height 32 + cardThick,
 * Opti applyCardThick), the note-lines clamp (metaRow), the streak badge gate (rail), plus the
 * card geometry the plan's anchors check (valdot 10, check 44).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardPrefsEffectTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val longNotes = (1..12).joinToString("\n") { "note line $it" }

    private fun daily(prefs: CardPrefs, task: TaskEntity, tags: List<TagEntity> = emptyList()) {
        rule.setContent {
            CardTestHost {
                CompositionLocalProvider(LocalCardPrefs provides prefs, LocalTags provides tags) {
                    DailyCardItem(task = task, onToggleCheck = {}, onEdit = {})
                }
            }
        }
    }

    private fun lineCount(tag: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().lineCount
    }

    @Test fun `default thickness is a 32 dp card`() {
        // A one-line todo is shorter than the min height, so the card is exactly 32 + 0.
        rule.setContent {
            CardTestHost { TodoCardItem(task = TaskEntity(id = "t1", type = "todo", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), onToggleCheck = {}, onEdit = {}) }
        }
        rule.onNodeWithTag("todo_card_t1").assertHeightIsEqualTo(32.dp)
    }

    @Test fun `a daily with the streak badge grows like Opti's rail`() {
        // Opti: rail padding 4 + two 15.7 px items + 3 gap + 4 = ~42 px > 32.
        daily(CardPrefs(), TaskEntity(id = "d1", type = "daily", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val h = rule.onNodeWithTag("daily_card_d1").getUnclippedBoundsInRoot().height
        assertTrue("daily card $h", h > 36.dp && h < 46.dp)
    }

    @Test fun `cardThick adds to the min height`() {
        daily(CardPrefs(cardThick = 40), TaskEntity(id = "d1", type = "daily", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("daily_card_d1").assertHeightIsEqualTo(72.dp)
    }

    @Test fun `todo and habit read the same thickness`() {
        rule.setContent {
            CardTestHost {
                CompositionLocalProvider(LocalCardPrefs provides CardPrefs(cardThick = 50)) {
                    androidx.compose.foundation.layout.Column {
                        TodoCardItem(task = TaskEntity(id = "t1", type = "todo", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), onToggleCheck = {}, onEdit = {})
                        HabitCardItem(task = TaskEntity(id = "h1", type = "habit", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), onScoreUp = {}, onScoreDown = {}, onLongPressUp = {}, onLongPressDown = {}, onEdit = {})
                    }
                }
            }
        }
        rule.onNodeWithTag("todo_card_t1").assertHeightIsEqualTo(82.dp)
        rule.onNodeWithTag("habit_card_h1").assertHeightIsEqualTo(82.dp)
    }

    @Test fun `notes clamp to notesLines`() {
        daily(CardPrefs(notesLines = 2), TaskEntity(id = "d1", type = "daily", title = "A", notes = longNotes, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(2, lineCount("card_notes"))
    }

    @Test fun `notes clamp to 5`() {
        daily(CardPrefs(notesLines = 5), TaskEntity(id = "d1", type = "daily", title = "A", notes = longNotes, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(5, lineCount("card_notes"))
    }

    @Test fun `notes off hides the preview`() {
        daily(CardPrefs(notesLines = 0), TaskEntity(id = "d1", type = "daily", title = "A", notes = longNotes, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onAllNodesWithTag("card_notes").assertCountEquals(0)
    }

    @Test fun `streak badge follows showStreaks`() {
        daily(CardPrefs(showStreaks = true), TaskEntity(id = "d1", type = "daily", title = "A", streak = 3, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("streak_d1").assertTextEquals("🔥 3")
    }

    @Test fun `streak badge shows zero like Opti`() {
        daily(CardPrefs(showStreaks = true), TaskEntity(id = "d1", type = "daily", title = "A", streak = 0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("streak_d1").assertTextEquals("🔥 0")
    }

    @Test fun `streak badges off hides it`() {
        daily(CardPrefs(showStreaks = false), TaskEntity(id = "d1", type = "daily", title = "A", streak = 3, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onAllNodesWithTag("streak_d1").assertCountEquals(0)
    }

    @Test fun `card geometry - valdot 10 and check 44`() {
        daily(CardPrefs(), TaskEntity(id = "d1", type = "daily", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("valdot").assertWidthIsEqualTo(10.dp)
        rule.onNodeWithTag("check_d1").assertWidthIsEqualTo(44.dp)
        rule.onNodeWithTag("ckbox").assertWidthIsEqualTo(18.dp)
    }

    @Test fun `rail shows difficulty, bell and subtask fraction`() {
        daily(
            CardPrefs(),
            TaskEntity(
                id = "d1", type = "daily", title = "A", difficulty = "medium",
                reminders = listOf(ReminderSpec(enabled = true)),
                checklist = listOf(ChecklistItem("s1", "one", true), ChecklistItem("s2", "two", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs(),
            ),
        )
        rule.onNodeWithTag("rail_bell").assertIsDisplayed()
        rule.onNodeWithTag("checklist_badge_d1").assertIsDisplayed()
        rule.onNodeWithTag("checklist_badge_d1").assertWidthIsEqualTo(32.dp)
    }

    @Test fun `tag chips resolve from LocalTags`() {
        daily(
            CardPrefs(),
            TaskEntity(id = "d1", type = "daily", title = "A", tags = listOf("t1", "gone"), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            tags = listOf(TagEntity("t1", "Work", "#bda8ff", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())),
        )
        rule.onNodeWithTag("tag_chip_t1").assertTextEquals("Work")
        rule.onAllNodesWithTag("tag_chips").assertCountEquals(1)
    }

    @Test fun `habit shows the counter rail item and Opti's minus glyph`() {
        rule.setContent {
            CardTestHost {
                HabitCardItem(task = TaskEntity(id = "h1", type = "habit", title = "A", cUp = 2, cDown = 5, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), onScoreUp = {}, onScoreDown = {}, onLongPressUp = {}, onLongPressDown = {}, onEdit = {})
            }
        }
        rule.onNodeWithTag("habit_count_h1").assertTextEquals("+2|−5")
        rule.onNodeWithTag("habit_up_h1").assertWidthIsEqualTo(44.dp)
        rule.onNodeWithTag("habit_down_h1").assertWidthIsEqualTo(44.dp)
    }
}
