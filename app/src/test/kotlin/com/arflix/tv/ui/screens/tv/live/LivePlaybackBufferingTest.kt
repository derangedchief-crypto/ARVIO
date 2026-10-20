package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.ui.PlayerView
import com.arflix.tv.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class)
class LivePlaybackBufferingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedNativeStartIgnoresStalePrepareFlag() {
        assertFalse(livePlaybackBuffering(true, false, true))
    }
    @Test fun actualNativeCachePauseStillShowsBuffering() {
        assertTrue(livePlaybackBuffering(true, true, false))
    }
    @Test fun terminalFailureDoesNotLeaveLoadingWheel() {
        assertFalse(livePlaybackBuffering(true, true, true, ownTvHasError = true))
    }
    @Test fun drmMedia3BufferingRemainsAuthoritative() {
        assertTrue(livePlaybackBuffering(false, false, true))
        assertFalse(livePlaybackBuffering(false, true, false))
    }
    @Test fun switchingToNativeIgnoresInactiveMedia3Buffering() {
        assertTrue(livePlaybackBuffering(false, false, true))
        assertFalse(livePlaybackBuffering(true, false, true))
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun rapidChannelStartsDoNotRequireAnotherNativeFlowEventToClearSpinner() {
        val nativeBuffer = MutableStateFlow(false)
        val legacyFlag = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val buffering by nativeBuffer.collectAsState()
            OwnTvPlayerHud(null, null, 0, isPlaying = true,
                isBuffering = livePlaybackBuffering(true, buffering, legacyFlag.value))
        }
        settle()
        repeat(3) {
            // The engine may start and finish within one frame, leaving its
            // collected value unchanged while prepare sets the old flag true.
            compose.runOnIdle { legacyFlag.value = true }
            settle()
            compose.onNodeWithTag("live-playback-buffering").assertDoesNotExist()
        }
    }

    @Test fun genuineRebufferingShowsEvenWhenPlayingFlagAndHiddenHudStayUnchanged() {
        val nativeBuffer = MutableStateFlow(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val buffering by nativeBuffer.collectAsState()
            OwnTvPlayerHud(null, null, 0, isPlaying = true,
                isBuffering = livePlaybackBuffering(true, buffering, true))
        }
        settle()
        compose.onNodeWithTag("live-playback-buffering").assertDoesNotExist()
        compose.runOnIdle { nativeBuffer.value = true }
        settle()
        compose.onNodeWithTag("live-playback-buffering").assertExists()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.onNodeWithTag("live-playback-buffering").assertExists()
        compose.runOnIdle { nativeBuffer.value = false }
        settle()
        compose.onNodeWithTag("live-playback-buffering").assertDoesNotExist()
    }

    @Test fun livePlayerViewDoesNotOwnASecondIndependentBufferingSpinner() {
        val view = LayoutInflater.from(RuntimeEnvironment.getApplication()).inflate(R.layout.live_tv_player_view, null) as PlayerView
        val mode = PlayerView::class.java.getDeclaredField("showBuffering").apply { isAccessible = true }.getInt(view)
        assertEquals(PlayerView.SHOW_BUFFERING_NEVER, mode)
        assertEquals(View.GONE, view.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).visibility)
    }
}
