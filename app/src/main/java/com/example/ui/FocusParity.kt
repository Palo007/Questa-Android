package com.example.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * WP-8.16-R1: focus and keyboard parity with Opti (7d5d4f3).
 *
 * Opti `openEdit` (app.js:5876-5886) calls `eTitle.focus(); eTitle.select()` right after the
 * sheet is shown, which on Android Chrome also raises the soft keyboard. [FocusOnOpen] does the
 * same for a Compose sheet: it waits for the field to be attached (the sheet opens in its own
 * window, so the first frames may not have it yet), requests focus, then shows the keyboard.
 */
@Composable
fun FocusOnOpen(requester: FocusRequester, key: Any? = Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(key) {
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            // requestFocus() throws while the requester's node is not attached yet.
            if (runCatching { requester.requestFocus() }.isSuccess) {
                keyboard?.show()
                return@LaunchedEffect
            }
        }
    }
}

private const val FOCUS_ATTEMPTS = 30

/** Keyboard options that match what Chrome Android gives Opti's inputs. */
object OptiKeyboard {
    /**
     * `<input type="text">` / `<textarea>`: Blink's default `autocapitalize` is "sentences",
     * so Chrome asks the keyboard to capitalise the first letter.
     */
    val Text = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

    /** A single-line text input whose Enter does something (save, add tag): Done key. */
    val TextDone = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)

    /**
     * Opti `#rCost` is `type="text"` parsed with `parseFloat`, so a decimal point must be
     * reachable. `KeyboardType.Number` has no "." on Gboard; `Decimal` has.
     */
    val Decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
}
