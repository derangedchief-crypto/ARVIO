package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class)
class PlaybackDiagnosticLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val message = mutableStateOf<PlaybackDiagnostic?>(null)
    private val playing = mutableStateOf(false)
    private fun start() = PlaybackDiagnostic("Starting live stream", "World News")
    private fun failure() = PlaybackDiagnostic("Playback failed", "World News", PlaybackDiagnosticSeverity.Error)
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PlaybackDiagnosticLifecycle(message.value, playing.value) { message.value = null }
            PlaybackDiagnosticBanner(message.value)
        }
        settle()
    }

    @Test fun startingMessageDismissesAsSoonAsPlaybackStarts() {
        message.value = start()
        mount()
        compose.onNodeWithText("Starting live stream").assertExists()
        compose.runOnIdle { playing.value = true }
        settle()
        compose.onNodeWithText("Starting live stream").assertDoesNotExist()
        compose.runOnIdle { assertNull(message.value) }
    }

    @Test fun newStartingMessageCannotStickWhenPlayingFlagDidNotChange() {
        playing.value = true
        mount()
        // Channel zapping can keep the engine's StateFlow true throughout.
        repeat(3) {
            compose.runOnIdle { message.value = start() }
            settle()
            compose.runOnIdle { assertNull(message.value) }
        }
    }

    @Test fun failureIsShownBrieflyThenDismissesEvenWithoutAnotherPlayerEvent() {
        message.value = failure()
        mount()
        compose.onNodeWithText("Playback failed").assertExists()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithText("Playback failed").assertDoesNotExist()
        compose.runOnIdle { assertNull(message.value) }
    }

    @Test fun failedNewSourceIsNotHiddenByPreviousChannelsPlayingFlag() {
        playing.value = true
        message.value = failure()
        mount()
        compose.onNodeWithText("Playback failed").assertExists()
        compose.mainClock.advanceTimeBy(3_100)
        compose.runOnIdle { assertNull(message.value) }
    }

    @Test fun oldFailureTimerCannotDismissNextChannelsStartupMessage() {
        message.value = failure()
        mount()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { message.value = start().copy(detail = "Next channel") }
        settle()
        compose.mainClock.advanceTimeBy(3_500)
        compose.onNodeWithText("Starting live stream").assertExists()
        compose.runOnIdle { playing.value = true }
        settle()
        compose.runOnIdle { assertNull(message.value) }
    }

    @Test fun retryMessageAlsoDismissesOnPlayback() {
        message.value = PlaybackDiagnostic("Retrying source", "World News", PlaybackDiagnosticSeverity.Warning)
        mount()
        compose.runOnIdle { playing.value = true }
        settle()
        compose.runOnIdle { assertNull(message.value) }
    }
}
