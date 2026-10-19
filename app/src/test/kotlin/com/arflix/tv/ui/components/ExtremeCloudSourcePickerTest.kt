package com.arflix.tv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
    private val vod = StreamSource("Masterplan (2026)", "IPTV VOD", "iptv_xtream_vod", "1080p", "",
        url = "https://provider.example.test/movie/55.mp4")

    private fun mount(device: DeviceType, streams: List<StreamSource> = listOf(vod), request: (() -> Unit)?,
        allowed: androidx.compose.runtime.State<Boolean>? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceType provides device) {
                Box(Modifier.requiredSize(if (device == DeviceType.TV) 1200.dp else 420.dp, 650.dp)) {
                    StreamSelector(true, streams, null, onRequestScrapers = if (allowed?.value != false) request else null)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun phoneShowsExtremeCloudAndRequiresConfirmationWithoutRemovingVod() {
        var calls = 0
        mount(DeviceType.PHONE, request = { calls++ })
        compose.onNodeWithText("Search scraper sources").assertDoesNotExist()
        compose.onNodeWithText("Search Extreme Cloud").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        assertTrue(compose.onAllNodesWithText("Masterplan", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Search Extreme Cloud").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-extreme-cloud-search").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        assertTrue(compose.onAllNodesWithText("Masterplan", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun tvFocusDoesNotStartCloudSearchUntilUserConfirms() {
        var calls = 0
        mount(DeviceType.TV, request = { calls++ })
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("confirm-extreme-cloud-search").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun cloudBackupRemainsAvailableWhenVodVerificationReturnedNoSources() {
        var calls = 0
        mount(DeviceType.PHONE, streams = emptyList(), request = { calls++ })
        compose.onNodeWithText("Search Extreme Cloud").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-extreme-cloud-search").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun noManualCallbackMeansNoCloudAction() {
        mount(DeviceType.PHONE, request = null)
        compose.onNodeWithText("Search Extreme Cloud").assertDoesNotExist()
    }
    @Test fun tvWithoutAccessDoesNotShowCloudMessageOrAction() {
        mount(DeviceType.TV, request = null)
        compose.onNodeWithText("Extreme Cloud", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertDoesNotExist()
    }
    @Test fun revokedAccessClosesConfirmationWithoutRunningSearch() {
        var calls = 0
        val allowed = androidx.compose.runtime.mutableStateOf(true)
        mount(DeviceType.PHONE, request = { calls++ }, allowed = allowed)
        compose.onNodeWithText("Search Extreme Cloud").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertExists()
        compose.runOnIdle { allowed.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Extreme Cloud", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls); allowed.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("confirm-extreme-cloud-search").assertDoesNotExist()
    }
}
