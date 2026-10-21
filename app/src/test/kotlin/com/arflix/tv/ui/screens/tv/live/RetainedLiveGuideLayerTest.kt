package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvProgram
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
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class RetainedLiveGuideLayerTest {
    @get:Rule val compose = createComposeRule()
    private val channel = IptvChannel("channel", "Channel", "https://example.invalid/live.ts", "News").enrich(1)
    private val now = 1_800_000L * 1_000_000

    @Test fun previewAndGuideStayMountedAcrossRepeatedFullscreenReturns() {
        val visible = mutableStateOf(true)
        val views = mutableSetOf<PlayerView>()
        var released = 0; var mounted = 0; var disposed = 0
        compose.setContent {
            Box(Modifier.requiredSize(1000.dp, 500.dp)) {
                RetainedLiveGuideLayer(visible.value) {
                    DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    OwnTvPreviewStage(channel, now, null, emptySet(), {}, guideMode = true,
                        onPlayerViewCreated = { views += it }, onPlayerViewReleased = { released++ })
                }
            }
        }
        compose.waitForIdle()
        repeat(5) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.onNodeWithText("Watch fullscreen").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("Watch fullscreen").assertExists()
        }
        compose.runOnIdle { assertEquals(1, mounted); assertEquals(0, disposed); assertEquals(1, views.size); assertEquals(0, released) }
    }

    @Test fun visitedGuideKeepsItsScrollWindowOnReturn() {
        val visible = mutableStateOf(true)
        val channels = (0..150).map { channel.copy(source = channel.source.copy(id = "row-$it", name = "Channel $it"), number = it + 1) }
        compose.setContent {
            Box(Modifier.requiredSize(900.dp, 420.dp)) {
                RetainedLiveGuideLayer(visible.value) {
                    TvGuideViewport(channels, clockTickMillis = now, indexedPrograms = emptyMap(),
                        fallbackNowNext = emptyMap(), channelNowNext = emptyMap(), selectedChannelId = null,
                        focusSelectedChannelSignal = 0, onChannelSelect = {}, onChannelFavoriteToggle = {}, favorites = emptySet())
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(96)
        compose.onNodeWithText("Channel 96").assertExists()
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        compose.runOnIdle { visible.value = true }; compose.waitForIdle()
        compose.onNodeWithText("Channel 96").assertExists()
        compose.onNodeWithText("Channel 0").assertDoesNotExist()
    }

    @Test fun hiddenGuideCannotStealFullscreenControlFocus() {
        val visible = mutableStateOf(true)
        val guide = FocusRequester()
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.requiredSize(900.dp, 500.dp)) {
                RetainedLiveGuideLayer(visible.value) {
                    TvStageAction("Guide action", modifier = Modifier.focusRequester(guide)) {}
                }
                TvStageAction("Fullscreen control", modifier = Modifier.testTag("fullscreen-control")) {}
            }
        }
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        compose.onNodeWithTag("fullscreen-control").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { guide.requestFocus() }
        compose.onNodeWithTag("fullscreen-control").assertIsFocused()
    }

    @Test fun directFullscreenEntryDoesNotBuildTheGuideUntilFirstReturn() {
        val visible = mutableStateOf(false)
        var mounted = 0; var disposed = 0
        compose.setContent {
            RetainedLiveGuideLayer(visible.value) {
                DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                TvStageAction("Guide") {}
            }
        }
        compose.runOnIdle { assertEquals(0, mounted); visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, mounted); visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, mounted); assertEquals(0, disposed) }
    }

    @Test fun returnAfterLongFullscreenSessionShowsCurrentTimeRatherThanStaleProgrammes() {
        val visible = mutableStateOf(true)
        val clock = mutableStateOf(now)
        var selected: IptvProgram? = null
        val minute = 60_000L
        val programmes = listOf(
            IptvProgram("Original current", startUtcMillis = now - 60 * minute, endUtcMillis = now + 60 * minute),
            IptvProgram("Original future", startUtcMillis = now + 60 * minute, endUtcMillis = now + 120 * minute),
            IptvProgram("Current after return", startUtcMillis = now + 120 * minute, endUtcMillis = now + 180 * minute))
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            Box(Modifier.requiredSize(900.dp, 420.dp)) {
                RetainedLiveGuideLayer(visible.value) {
                    TvGuideViewport(listOf(channel), clockTickMillis = clock.value,
                        indexedPrograms = mapOf(channel.id to programmes), fallbackNowNext = emptyMap(), channelNowNext = emptyMap(),
                        selectedChannelId = channel.id, focusSelectedChannelSignal = 0, onChannelSelect = {},
                        onProgramSelect = { _, programme -> selected = programme }, onChannelFavoriteToggle = {}, favorites = emptySet())
                }
            }
        }
        fun focusProgramme() = compose.onNodeWithTag("guide-programmes-${channel.id}")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        focusProgramme()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("Original future", selected?.title); visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { clock.value = now + 150 * minute }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        focusProgramme()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("Current after return", selected?.title) }
    }
}
