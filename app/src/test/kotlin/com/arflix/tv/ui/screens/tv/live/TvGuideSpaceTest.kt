package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import com.arflix.tv.data.model.*
import com.arflix.tv.ui.components.SidebarItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvGuideSpaceTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile(id = "fixture", name = "Viewer")
    private val now = java.time.Instant.parse("2026-10-20T20:10:00Z").toEpochMilli()
    private val programme = IptvProgram("Live: MLB Baseball", "American League Championship Series, Game 6.", now - 600_000, now + 7_200_000)
    private val guide = IptvNowNext(now = programme, next = programme.copy(title = "Sportsnet Central", startUtcMillis = programme.endUtcMillis, endUtcMillis = programme.endUtcMillis + 1_800_000))
    private val channels = (1..40).map { i -> EnrichedChannel(
        IptvChannel("channel-$i", "CA | SPORTNET $i", "https://example.invalid/live/$i", "CA: SPORTS"),
        1247 + i, "CA", Genre.Sports, Quality.HD, "EN", Color.Black, Color.White, false) }
    private val all = LiveCategory("all", "All channels", 40, CategoryIcon.All)
    private val sports = LiveCategory("sports", "CA: SPORTS", 40, CategoryIcon.All)
    private val tree = LiveCategoryTree(listOf(all), LiveSection("global", "YOUR CHANNELS", listOf(sports)),
        LiveSection("countries", "COUNTRIES", emptyList()), LiveSection("adult", "ADULT", emptyList()))

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("tv-guide-fixture").captureToImage().asAndroidBitmap()
        File("/tmp/opencode/$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun settleMenuFocus() {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun checkSpace(width: Int, height: Int, minimumRows: Int) {
        val dense = mutableStateOf(false)
        val menuOpen = mutableStateOf(false)
        val menuIndex = mutableIntStateOf(0)
        var guideHeight = 0
        var visibleRows = 0
        var player: PlayerView? = null
        compose.setContent {
            Box(Modifier.requiredSize(width.dp, height.dp).background(TvStageStyle.Background).testTag("tv-guide-fixture")) {
                Column(Modifier.fillMaxSize()) {
                    if (!dense.value) OwnTvScreenHeader(profile, -1, {}, {}, {})
                    Row(Modifier.weight(1f)) {
                        OwnTvCategoryPanel(tree, "sports", expanded = true, listState = rememberLazyListState(), onSelect = {}, onOpenSearch = {}, modifier = Modifier.fillMaxHeight())
                        Column(Modifier.weight(1f)) {
                            OwnTvPreviewStage(channels.first(), now, guide, emptySet(), {},
                                onPlayerViewCreated = { player = it }, guideMode = dense.value,
                                modifier = Modifier.fillMaxWidth().padding(end = if (dense.value) 48.dp else 0.dp))
                            GuideBackfillStatus(true, 46, "Checking remaining channel listings", compact = dense.value)
                            OwnTvGuideHeading(40, compact = dense.value)
                            TvGuideViewport(channels, clockTickMillis = now,
                                indexedPrograms = channels.associate { it.id to listOf(programme) },
                                fallbackNowNext = emptyMap(), channelNowNext = emptyMap(), selectedChannelId = channels.first().id,
                                focusSelectedChannelSignal = 0, onChannelSelect = {}, onChannelFavoriteToggle = {}, favorites = emptySet(),
                                onVisibleChannelsChanged = { first, last -> visibleRows = last - first + 1 },
                                modifier = Modifier.weight(1f).onGloballyPositioned { guideHeight = it.size.height })
                        }
                    }
                }
                if (dense.value) TvGuideNavigationOverlay(profile, menuOpen.value, menuIndex.intValue,
                    { menuOpen.value = true }, { menuOpen.value = false }, { menuIndex.intValue = it }, {}, {})
            }
        }
        compose.waitForIdle()
        val before = guideHeight
        val rowsBefore = visibleRows
        capture("tv-guide-before-$width")
        compose.runOnIdle { dense.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Guide grew only ${guideHeight - before}px", guideHeight - before >= 150)
            assertTrue("Guide viewport is $guideHeight/$height", guideHeight >= height * 0.60f)
            assertTrue("Only $visibleRows channel rows visible", visibleRows >= minimumRows)
            assertTrue("Rows failed to improve: $rowsBefore to $visibleRows", visibleRows >= rowsBefore + 4)
            val video = requireNotNull(player)
            assertTrue("Preview remained too large: ${video.width}x${video.height}", video.width <= 201 && video.height <= 114)
            assertEquals(16f / 9f, video.width.toFloat() / video.height, 0.025f)
            System.err.println("[TV-Guide-Space] ${width}x$height guide=$before->$guideHeight rows=$rowsBefore->$visibleRows preview=${video.width}x${video.height}")
        }
        compose.onNodeWithText("Profile").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation menu").assertExists()
        capture("tv-guide-after-$width")
        val expandedGuideHeight = guideHeight
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        settleMenuFocus()
        compose.runOnIdle { assertEquals("Drawer must overlay, not shrink the guide", expandedGuideHeight, guideHeight) }
        capture("tv-guide-menu-$width")
    }

    @Test fun hdTelevisionGuideGetsMostOfTheScreenDuringLoading() = checkSpace(960, 540, 8)
    @Test fun fullHdLogicalLayoutShowsAtLeastTwelveRows() = checkSpace(1280, 720, 12)

    @Test fun navigationIsHiddenUntilOpenedAndBackDoesNotNavigateAway() {
        val visible = mutableStateOf(false)
        val index = mutableIntStateOf(0)
        var navigations = 0
        compose.setContent {
            TvGuideNavigationOverlay(profile, visible.value, index.intValue,
                onOpen = { visible.value = true }, onDismiss = { visible.value = false },
                onFocusIndex = { index.intValue = it }, onNavigate = { navigations++ }, onSwitchProfile = { navigations++ })
        }
        compose.onNodeWithText("Profile").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        settleMenuFocus()
        compose.onNodeWithTag("tv-navigation-drawer").assertIsFocused()
        compose.onNodeWithText("Profile").assertExists()
        compose.onNodeWithText("Settings").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        settleMenuFocus()
        compose.onNodeWithText("Profile").assertDoesNotExist()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, navigations) }
    }

    @Test fun rightDrawerKeepsEveryDestinationAndProfileAction() {
        val visible = mutableStateOf(true)
        val index = mutableIntStateOf(0)
        val destinations = mutableListOf<SidebarItem>()
        var profiles = 0
        compose.setContent {
            TvGuideNavigationOverlay(profile, visible.value, index.intValue, {}, { visible.value = false },
                { index.intValue = it }, { destinations += it }, { profiles++ })
        }
        listOf("Search", "Home", "Library", "Live TV", "Settings").forEach { label ->
            compose.onNodeWithText(label).performClick()
        }
        compose.onNodeWithText("Profile").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SidebarItem.SEARCH, SidebarItem.HOME, SidebarItem.WATCHLIST, SidebarItem.TV, SidebarItem.SETTINGS), destinations)
            assertEquals(1, profiles)
        }
    }

    @Test fun remoteUpDownMovesThroughRightMenuWithoutActivatingDestination() {
        val index = mutableIntStateOf(0)
        var selected: SidebarItem? = null
        compose.setContent {
            TvGuideNavigationOverlay(profile, true, index.intValue, {}, {}, { index.intValue = it }, { selected = it }, {})
        }
        compose.waitForIdle()
        settleMenuFocus()
        compose.onNodeWithTag("tv-navigation-drawer").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        settleMenuFocus()
        compose.runOnIdle { assertEquals(1, index.intValue); assertNull(selected) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(SidebarItem.SEARCH, selected) }
    }

    @Test fun backClosesTheDrawerWithoutActivatingNavigation() {
        val visible = mutableStateOf(true)
        var navigations = 0
        compose.setContent {
            TvGuideNavigationOverlay(profile, visible.value, 0, {}, { visible.value = false }, {},
                { navigations++ }, { navigations++ })
        }
        settleMenuFocus()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        settleMenuFocus()
        compose.onNodeWithText("Navigation").assertDoesNotExist()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, navigations) }
    }
}
