package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LivePreviewBackShortcutTest {
    @get:Rule val compose = createComposeRule()
    private fun verify(zoneName: String) {
        compose.mainClock.autoAdvance = false
        val fullscreen = mutableStateOf(false)
        val zone = mutableStateOf("Categories")
        var promoted = 0; var short = 0; var leaked = 0
        val promotedStreams = mutableListOf<String>()
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            val back = rememberLivePreviewBackShortcut(!fullscreen.value,
                onShortBack = { short++ },
                onLongBack = { promoted++; promotedStreams += "currently-playing.ts"; fullscreen.value = true })
            Box(Modifier.size(500.dp, 200.dp).testTag("back-shortcut")
                .onPreviewKeyEvent(back).onKeyEvent { event ->
                    if (event.key == Key.Back) { leaked++; fullscreen.value = false; true } else false
                }.focusable()) {
                Text(if (fullscreen.value) "Fullscreen current stream" else zone.value)
            }
        }
        compose.onNodeWithTag("back-shortcut").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("back-shortcut").assertIsFocused()
        listOf(zoneName).forEach { target ->
            compose.runOnIdle { zone.value = target; fullscreen.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onRoot().performKeyInput { keyDown(Key.Back) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(700)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(700))
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("zone=$target promoted=$promoted short=$short leaked=$leaked", fullscreen.value) }
            compose.onNodeWithText("Fullscreen current stream").assertExists()
            compose.onRoot().performKeyInput { keyUp(Key.Back) }
            compose.onNodeWithText("Fullscreen current stream").assertExists()
        }
        compose.runOnIdle {
            assertEquals(1, promoted); assertEquals(0, short); assertEquals(0, leaked)
            assertEquals(listOf("currently-playing.ts"), promotedStreams)
        }
    }
    @Test fun heldBackFromCategoriesPromotesCurrentStreamAndConsumesRelease() = verify("Categories")
    @Test fun heldBackFromAnotherChannelRowDoesNotChangePlayingStream() = verify("Other channel row")
    @Test fun heldBackFromFutureGuidePromotesCurrentStreamAndConsumesRelease() = verify("Future EPG programme")
}
