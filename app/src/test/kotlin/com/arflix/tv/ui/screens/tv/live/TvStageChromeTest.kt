package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import androidx.media3.ui.PlayerView
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
class TvStageChromeTest {
    @get:Rule val compose = createComposeRule()
    private val all = LiveCategory("all", "All Channels", 48, CategoryIcon.All)
    private val news = LiveCategory("news", "News", 12, CategoryIcon.News,
        playlistGroupName = "News", playlistId = "provider")
    private val tree = LiveCategoryTree(listOf(all), LiveSection("global", "PLAYLIST", listOf(news)),
        LiveSection("countries", "COUNTRIES", emptyList()), LiveSection("adult", "ADULT", emptyList()))
    private val channel = EnrichedChannel(IptvChannel("demo", "World News HD", "https://example.invalid/live", "News"),
        1, "US", Genre.News, Quality.HD, "EN", Color.Black, Color.White, false)
    private val now = 1_800_000L * 1_000_000
    private val programme = IptvProgram("The world this evening", "A closer look at today's headlines and the stories behind them.", now, now + 3_600_000L)

    @Test fun longCategoryKeepsChannelRowsAcrossProgrammeWindowBoundaries() {
        val channels = (0 until 350).map { index ->
            channel.copy(source = channel.source.copy(id = "row-$index", name = "Channel $index"), number = index + 1)
        }
        var firstVisible = -1
        compose.setContent {
            Box(Modifier.requiredSize(800.dp, 420.dp)) {
                TvGuideViewport(channels, clockTickMillis = now, indexedPrograms = emptyMap(),
                    fallbackNowNext = emptyMap(), channelNowNext = emptyMap(), selectedChannelId = null,
                    focusSelectedChannelSignal = 0, onChannelSelect = {}, onChannelFavoriteToggle = {},
                    favorites = emptySet(), onVisibleChannelsChanged = { first, _ -> firstVisible = first })
            }
        }
        listOf(48, 96, 180, 280, 12).forEach { index ->
            compose.onNode(hasScrollAction()).performScrollToIndex(index)
            compose.onNodeWithText("Channel $index").assertExists()
            compose.runOnIdle { assertEquals(index, firstVisible) }
        }
    }

    @Test fun categorySheetSelectsRealCategoryId() {
        var selected: String? = null
        compose.setContent {
            Box(Modifier.width(400.dp)) {
                OwnTvCategorySheetBar(tree, "all", onSelect = { selected = it }, onOpenSearch = {})
            }
        }
        compose.onNodeWithText("All Channels ▾").performClick()
        compose.onNodeWithText("News").performClick()
        compose.runOnIdle { assertEquals("news", selected) }
    }

    @Test fun previewWatchActionIsConnected() {
        var opened = false
        compose.setContent {
            OwnTvPreviewStage(channel, now, IptvNowNext(now = programme), emptySet(), {},
                onFullscreenClick = { opened = true }, modifier = Modifier.width(800.dp))
        }
        compose.onNodeWithText("Watch fullscreen").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun foldPreviewPlateUsesWidescreenDimensions() {
        var view: PlayerView? = null
        compose.setContent {
            Box(Modifier.width(510.dp)) {
                OwnTvPreviewStage(channel, now, IptvNowNext(now = programme), emptySet(), {},
                    onPlayerViewCreated = { view = it })
            }
        }
        compose.runOnIdle {
            val preview = requireNotNull(view)
            assertEquals(16f / 9f, preview.width.toFloat() / preview.height, 0.015f)
        }
    }

    @Test fun folderBrowserRetainsCategoryAndManagementCallbacks() {
        var selected: String? = null
        var hidden: String? = null
        compose.setContent {
            OwnTvCategoryPanel(tree, "all", expanded = true, listState = rememberLazyListState(),
                onSelect = { selected = it }, onOpenSearch = {}, onHideCategory = { _, group -> hidden = group })
        }
        compose.onNodeWithText("News").performClick()
        compose.runOnIdle { assertEquals("news", selected) }
        compose.onNodeWithText("News").performTouchInput { longClick() }
        compose.onNodeWithText("Hide category").performClick()
        compose.runOnIdle { assertEquals("News", hidden) }
    }

    @Test fun desktopStageRendersAndCapturesPreview() {
        compose.setContent {
            Column(Modifier.requiredSize(1200.dp, 700.dp).background(TvStageStyle.Background)) {
                OwnTvScreenHeader(null, -1, {}, {}, {})
                Row(Modifier.fillMaxSize()) {
                    OwnTvCategoryPanel(tree, "all", expanded = true, listState = rememberLazyListState(),
                        onSelect = {}, onOpenSearch = {}, modifier = Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f)) {
                        OwnTvPreviewStage(channel, now, IptvNowNext(now = programme), emptySet(), {})
                        OwnTvGuideHeading(48)
                        TvGuideViewport(listOf(channel), clockTickMillis = now,
                            indexedPrograms = mapOf(channel.id to listOf(programme)),
                            fallbackNowNext = emptyMap(), channelNowNext = emptyMap(),
                            selectedChannelId = channel.id, focusSelectedChannelSignal = 0,
                            onChannelSelect = {}, onChannelFavoriteToggle = {}, favorites = emptySet(),
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.onNodeWithText("Your channels. Your programme guide.").assertExists()
        compose.onNodeWithText("Programme guide").assertExists()
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File("/tmp/opencode/tv-stage-desktop.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hiddenFoldersStayManagementOnly() {
        val hidden = news.copy(id = "hidden-news")
        val entries = tvStageFolders(tree.copy(hidden = LiveSection("hidden", "HIDDEN", listOf(hidden))), emptyList())
        assertTrue(entries.single { it.category.id == hidden.id }.hidden)
        assertEquals(entries.size, entries.map { it.category.id }.distinct().size)
    }

    @Test fun fullscreenControlsKeepPlayerCallbacks() {
        var paused = false
        var guide = false
        var live = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OwnTvPlayerHud(channel, IptvNowNext(now = programme), pokeSignal = 0,
                isCatchupMode = true, playbackDurationMs = 3_600_000L,
                onPlayPauseClick = { paused = true }, onGuideClick = { guide = true },
                onGoLiveClick = { live = true })
        }
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Programme guide").performClick()
        compose.onNodeWithText("Go live").performClick()
        compose.runOnIdle { assertTrue(paused); assertTrue(guide); assertTrue(live) }
    }

    @Test fun fullscreenOverlayHidesAndReportsVisibility() {
        var shown: Boolean? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OwnTvPlayerHud(channel, IptvNowNext(now = programme), pokeSignal = 0,
                onPlayPauseClick = {}, onVisibilityChanged = { shown = it })
        }
        compose.runOnIdle { assertEquals(true, shown) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, shown) }
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-mdpi")
    fun phoneStageRendersAndCapturesPreview() {
        compose.setContent {
            Column(Modifier.requiredSize(400.dp, 880.dp).background(TvStageStyle.Background)) {
                OwnTvPreviewStage(channel, now, IptvNowNext(now = programme), emptySet(), {})
                OwnTvCategorySheetBar(tree, "all", onSelect = {}, onOpenSearch = {})
                OwnTvGuideHeading(48)
                TvGuideViewport(listOf(channel), clockTickMillis = now,
                    indexedPrograms = mapOf(channel.id to listOf(programme)), fallbackNowNext = emptyMap(),
                    channelNowNext = emptyMap(), selectedChannelId = channel.id, focusSelectedChannelSignal = 0,
                    onChannelSelect = {}, onChannelFavoriteToggle = {}, favorites = emptySet(), compact = true,
                    modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("All Channels ▾").assertExists()
        compose.onNodeWithText("Watch fullscreen").assertExists()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File("/tmp/opencode/tv-stage-phone.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
