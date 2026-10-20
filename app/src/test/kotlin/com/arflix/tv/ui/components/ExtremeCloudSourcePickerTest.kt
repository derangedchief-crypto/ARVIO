package com.arflix.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.LocalDeviceType
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
class ExtremeCloudSourcePickerTest {
    @get:Rule val compose = createComposeRule()
    private val cloud = StreamSource("Masterplan (2026)", "Torrentio PM", "torrentio-instance", "1080p", "4 GB",
        url = "https://cloud.example.test/movie.mp4", displayAddonName = "Extreme Cloud")
    private fun mount(device: DeviceType, streams: List<StreamSource>, onSelect: (StreamSource) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceType provides device) {
                Box(Modifier.requiredSize(if (device == DeviceType.TV) 1200.dp else 420.dp, 650.dp).testTag("cloud-source-fixture")) {
                    StreamSelector(true, streams, null, onSelect = onSelect)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun assertNoPermissionUi() {
        compose.onNodeWithText("Search Extreme Cloud").assertDoesNotExist()
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertDoesNotExist()
        compose.onNodeWithText("may use points", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Torrentio PM", substring = true).assertDoesNotExist()
    }
    @Test fun phoneShowsExtremeCloudLinksDirectlyWithoutPrompt() {
        var selected: StreamSource? = null
        mount(DeviceType.PHONE, listOf(cloud)) { selected = it }
        assertNoPermissionUi()
        assertTrue(compose.onAllNodesWithText("Extreme Cloud", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Masterplan", substring = true).performClick()
        compose.runOnIdle { assertEquals(cloud, selected) }
    }
    @Test fun tvSelectsExtremeCloudLinkWithoutPermissionPopup() {
        var selected: StreamSource? = null
        mount(DeviceType.TV, listOf(cloud)) { selected = it }
        assertNoPermissionUi()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(cloud, selected) }
        assertNoPermissionUi()
    }
    @Test fun emptyUnauthorizedResultHasNoCloudActionOrPrompt() {
        mount(DeviceType.PHONE, emptyList())
        assertNoPermissionUi()
        compose.onNodeWithText("Extreme Cloud", substring = true).assertDoesNotExist()
    }
    @Test fun vodOnlyResultDoesNotShowCloudOptions() {
        mount(DeviceType.PHONE, listOf(cloud.copy(addonName = "IPTV VOD", addonId = "iptv_xtream_vod", displayAddonName = null)))
        assertNoPermissionUi()
        compose.onNodeWithText("Extreme Cloud", substring = true).assertDoesNotExist()
    }
    @Test fun cloudGroupDoesNotChangeBackendIdentity() {
        val other = cloud.copy(addonId = "another-provider", addonName = "Other scraper")
        val tabs = buildSourceAddonTabs(listOf(cloud, other), emptyList())
        assertEquals(listOf("Extreme Cloud"), tabs.map { it.label })
        assertEquals("torrentio-instance", cloud.addonId)
        assertEquals("Torrentio PM", cloud.addonName)
        assertNotEquals(sourceStreamRowKey(cloud, 0), sourceStreamRowKey(other, 1))
    }
    @Test fun authorizedCustomerCanSeeAndChooseCloudEvenWithBeekeeperVodPresent() {
        val local = cloud.copy(source = "The Beekeeper (2024)", addonName = "IPTV VOD", addonId = "iptv_xtream_vod",
            quality = "1080p", size = "", displayAddonName = null, url = "https://provider.example.test/movie.mp4")
        val backup = cloud.copy(source = "The.Beekeeper.2024.2160p", quality = "4K", size = "52 GB")
        var selected: StreamSource? = null
        mount(DeviceType.PHONE, listOf(backup, local)) { selected = it }
        assertNoPermissionUi()
        assertEquals(listOf("IPTV VOD", "Extreme Cloud"), buildSourceAddonTabs(listOf(backup, local), emptyList()).map { it.label })
        assertTrue(compose.onAllNodesWithText("IPTV VOD").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Extreme Cloud").fetchSemanticsNodes().isNotEmpty())
        val image = compose.onNodeWithTag("cloud-source-fixture").captureToImage().asAndroidBitmap()
        java.io.File("/tmp/opencode/extreme-cloud-with-vod-phone.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onAllNodesWithText("Extreme Cloud").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("The.Beekeeper", substring = true).performClick()
        compose.runOnIdle { assertEquals(backup, selected) }
    }
    @Test fun tvDefaultsToVodButAllowsExplicitCloudBackupSelection() {
        val local = cloud.copy(addonName = "IPTV VOD", addonId = "iptv_xtream_vod", displayAddonName = null)
        val backup = cloud.copy(quality = "4K", size = "52 GB")
        val choices = mutableListOf<StreamSource>()
        mount(DeviceType.TV, listOf(backup, local)) { choices += it }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(local), choices) }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(local, backup), choices) }
        assertNoPermissionUi()
    }
}
