package com.example.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.14 (PX-88 provider): CardPrefs defaults are Opti's migrate() defaults; the provider follows the DataStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardPrefsProviderTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test
    fun `defaults - notesLines 3, cardThick 0, streaks shown, width 480`() {
        assertEquals(CardPrefs(3, 0, true, 480), CardPrefs.from(emptyPreferences()))
        assertEquals(CardPrefs(), CardPrefs.from(emptyPreferences()))
    }

    @Test
    fun `stored values are read and cardThick is clamped to 0-60`() {
        val p = mutablePreferencesOf(
            SyncKeys.NOTES_LINES to 0, SyncKeys.CARD_THICK to 99,
            SyncKeys.SHOW_STREAKS to false, SyncKeys.WIDTH_PX to 720,
        )
        assertEquals(CardPrefs(0, 60, false, 720), CardPrefs.from(p))
        assertEquals(0, CardPrefs.from(mutablePreferencesOf(SyncKeys.CARD_THICK to -5)).cardThick)
    }

    @Test
    fun `flow drops unrelated edits`() = runBlocking {
        val data = MutableStateFlow<Preferences>(mutablePreferencesOf(SyncKeys.NOTES_LINES to 5))
        assertEquals(5, CardPrefs.flow(data).first().notesLines)
    }

    @Test
    fun `LocalCardPrefs defaults outside a provider and follows the provided value`() {
        val data = MutableStateFlow<Preferences>(emptyPreferences())
        rule.setContent {
            val prefs by CardPrefs.flow(data).collectAsState(initial = CardPrefs())
            CompositionLocalProvider(LocalCardPrefs provides prefs) {
                Text("lines=" + LocalCardPrefs.current.notesLines + " streaks=" + LocalCardPrefs.current.showStreaks)
            }
        }
        rule.onNodeWithText("lines=3 streaks=true").assertExists()
        data.value = mutablePreferencesOf(SyncKeys.NOTES_LINES to 1, SyncKeys.SHOW_STREAKS to false)
        rule.waitForIdle()
        rule.onNodeWithText("lines=1 streaks=false").assertExists()
    }
}
