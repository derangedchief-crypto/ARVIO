package com.arflix.tv.ui.screens.tv.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** Re-check each new message even when playback stayed true across a channel switch. */
@Composable
internal fun PlaybackDiagnosticLifecycle(
    diagnostic: PlaybackDiagnostic?,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(diagnostic, isPlaying) {
        val current = diagnostic ?: return@LaunchedEffect
        if (current.severity == PlaybackDiagnosticSeverity.Error) {
            // A terminal failure is a brief notification, not a permanent overlay.
            delay(3_000L)
            dismiss()
        } else if (isPlaying) {
            dismiss()
        }
    }
}
