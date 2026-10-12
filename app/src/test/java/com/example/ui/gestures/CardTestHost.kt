package com.example.ui.gestures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.FloatingFeedback
import com.example.ui.LocalFeedbacks
import kotlinx.coroutines.flow.MutableSharedFlow

/** The CompositionLocals a Dashboard card reads, with recording fakes. */
@Composable
internal fun CardTestHost(
    buzz: Buzz = Buzz { },
    drag: DragActivity = DragActivity(),
    gated: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFeedbacks provides MutableSharedFlow<FloatingFeedback>(),
        LocalBuzz provides buzz,
        LocalDragActivity provides drag,
        LocalInputGated provides gated,
    ) {
        Box(Modifier.width(360.dp)) { content() }
    }
}
