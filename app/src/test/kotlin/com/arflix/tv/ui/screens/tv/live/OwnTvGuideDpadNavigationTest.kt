package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
class OwnTvGuideDpadNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_800_000L * 1_000_000
    private val minute = 60_000L
    private val channels = (1..3).map {
        IptvChannel("row-$it", "Channel $it", "https://example.invalid/$it.ts", "Sports").enrich(it)
    }
    private val programmes = listOf(
        IptvProgram("Earlier programme", startUtcMillis = now - 75 * minute, endUtcMillis = now - 15 * minute),
        IptvProgram("Current programme", startUtcMillis = now - 15 * minute, endUtcMillis = now + 45 * minute),
        IptvProgram("Next programme", startUtcMillis = now + 45 * minute, endUtcMillis = now + 105 * minute),
        IptvProgram("Later programme", startUtcMillis = now + 105 * minute, endUtcMillis = now + 165 * minute),
    )
    private var playedChannels = 0
    private var selectedProgramme: IptvProgram? = null

    private fun mount(withListings: Boolean = true) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            val categoryFocus = remember { FocusRequester() }
            var mode by remember { mutableStateOf(EpgGridFocusMode.ChannelList) }
            var selectedId by remember { mutableStateOf(channels.first().id) }
            var channelSignal by remember { mutableIntStateOf(1) }
            var epgSignal by remember { mutableIntStateOf(0) }
            var categorySignal by remember { mutableIntStateOf(0) }
            val category = LiveCategory("all", "All Channels", channels.size, CategoryIcon.All)
            val tree = LiveCategoryTree(listOf(category), LiveSection("playlist", "PLAYLIST", emptyList()),
                LiveSection("countries", "COUNTRIES", emptyList()), LiveSection("adult", "ADULT", emptyList()))
            Row(Modifier.requiredSize(1100.dp, 430.dp)) {
                OwnTvCategoryPanel(tree, "all", expanded = true, listState = rememberLazyListState(),
                    focusRequester = categoryFocus, onSelect = {}, onOpenSearch = {},
                    focusCategorySignal = categorySignal,
                    onMoveRight = { mode = EpgGridFocusMode.ChannelList; channelSignal++ },
                    modifier = Modifier.fillMaxHeight())
                TvGuideViewport(channels, clockTickMillis = now,
                    indexedPrograms = if (withListings) channels.associate { it.id to programmes } else emptyMap(),
                    fallbackNowNext = emptyMap(), channelNowNext = emptyMap(), selectedChannelId = selectedId,
                    focusSelectedChannelSignal = channelSignal, focusEpgSignal = epgSignal, focusMode = mode,
                    onChannelSelect = { playedChannels++ },
                    onProgramSelect = { _, programme -> selectedProgramme = programme },
                    onChannelFocused = { selectedId = it.id }, onChannelFavoriteToggle = {}, favorites = emptySet(),
                    onMoveLeftFromChannels = { categorySignal++; categoryFocus.requestFocus() },
                    onEnterEpg = { selectedId = it.id; mode = EpgGridFocusMode.Epg; epgSignal++ },
                    onExitEpg = { channel -> selectedId = channel?.id ?: selectedId; mode = EpgGridFocusMode.ChannelList; channelSignal++ },
                    modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("guide-channel-row-1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
    private fun channelFocused(row: Int) {
        try { compose.onNodeWithTag("guide-channel-row-$row").assertIsFocused() }
        catch (error: AssertionError) { throw AssertionError(compose.onRoot().printToString(), error) }
    }
    private fun programmeFocused(row: Int) = compose.onNodeWithTag("guide-programmes-row-$row").assertIsFocused()

    @Test fun arrowsAloneRoundTripFromGuideThroughChannelsCategoriesAndBack() {
        mount()
        channelFocused(1)
        key(Key.DirectionRight); programmeFocused(1)
        key(Key.DirectionLeft); channelFocused(1)
        key(Key.DirectionLeft)
        compose.onNodeWithText("All Channels").assertIsFocused()
        key(Key.DirectionRight); channelFocused(1)
        key(Key.DirectionRight); programmeFocused(1)
        assertEquals(0, playedChannels)
        assertNull(selectedProgramme)
    }

    @Test fun leftExitsGuideEvenAfterBrowsingFutureProgrammes() {
        mount()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        programmeFocused(1)
        key(Key.DirectionLeft)
        channelFocused(1)
    }

    @Test fun returningAcrossColumnsPreservesTheFocusedChannelRow() {
        mount()
        key(Key.DirectionRight)
        key(Key.DirectionDown); programmeFocused(2)
        key(Key.DirectionLeft); channelFocused(2)
        key(Key.DirectionLeft)
        key(Key.DirectionRight); channelFocused(2)
        key(Key.DirectionRight); programmeFocused(2)
        assertEquals(0, playedChannels)
    }

    @Test fun emptyGuideRowsAlsoExitLeftWithoutWalkingHistory() {
        mount(withListings = false)
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        key(Key.DirectionLeft)
        channelFocused(1)
    }

    @Test fun explicitRewindStillAllowsSelectingHistoricalProgrammes() {
        mount()
        key(Key.DirectionRight)
        key(Key.MediaRewind)
        key(Key.Enter)
        assertEquals("Earlier programme", selectedProgramme?.title)
        key(Key.DirectionLeft)
        channelFocused(1)
    }
}
