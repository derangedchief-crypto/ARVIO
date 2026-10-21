package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalLiveGuideInteractive = staticCompositionLocalOf { true }

/** Keep the visited guide and mini-player mounted while fullscreen owns focus.
 * A direct fullscreen launch still defers the guide until it is first needed. */
@Composable
internal fun RetainedLiveGuideLayer(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var visited by remember { mutableStateOf(visible) }
    SideEffect { if (visible) visited = true }
    if (!visible && !visited) return
    CompositionLocalProvider(LocalLiveGuideInteractive provides visible) {
        Box(modifier.fillMaxSize()
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .focusProperties { onEnter = { if (!visible) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { !visible }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .pointerInput(visible) {
                if (!visible) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}
