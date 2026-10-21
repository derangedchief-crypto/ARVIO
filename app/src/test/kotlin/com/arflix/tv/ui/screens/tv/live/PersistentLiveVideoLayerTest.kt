package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.ui.PlayerView
import com.arflix.tv.data.model.IptvChannel
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
class PersistentLiveVideoLayerTest {
    @get:Rule val compose = createComposeRule()
    private val channel = IptvChannel("one", "Channel", "https://example.invalid/live.ts", "News").enrich(1)

    @Test fun repeatedPresentationsKeepOneViewHolderAndOneOutputBinding() {
        val fullscreen = mutableStateOf(false)
        val views = mutableSetOf<PlayerView>()
        val holders = mutableSetOf<SurfaceHolder>()
        var releases = 0; var bindings = 0; var detaches = 0
        // Robolectric's fake SurfaceHolder returns no native Surface. Use its
        // identity as the output token; native continuity needs device verification.
        val output = OwnTvSurfaceHandoff<SurfaceHolder, SurfaceHolder>(valid = { true },
            attach = { _, _, _ -> bindings++ }, detach = { _, _ -> detaches++ }, resize = { _, _, _ -> })
        output.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        compose.setContent {
            Box(Modifier.requiredSize(1100.dp, 620.dp)) {
                RetainedLiveGuideLayer(!fullscreen.value) {
                    OwnTvPreviewStage(channel, 1_800_000L, null, emptySet(), {}, externalVideo = true,
                        onPlayerViewCreated = { fail("Logical guide slot created a second PlayerView") })
                }
                PersistentLiveVideoLayer(
                    bounds = if (fullscreen.value) Rect(0f, 0f, 1100f, 620f) else Rect(240f, 16f, 440f, 128f),
                    visible = true, fullscreen = fullscreen.value, resetKey = 0, channel = channel, onOpenFullscreen = {},
                    onCreated = { view ->
                        views += view
                        val holder = (view.videoSurfaceView as SurfaceView).holder
                        holders += holder
                        output.request(holder, holder)
                    }, onReleased = { releases++ })
            }
        }
        compose.waitForIdle()
        repeat(10) {
            compose.runOnIdle { fullscreen.value = true }; compose.waitForIdle()
            compose.runOnIdle { fullscreen.value = false }; compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(1, views.size); assertEquals(1, holders.size)
            assertEquals(1, bindings); assertEquals(0, detaches); assertEquals(0, releases)
        }
    }

    @Test fun onlyExplicitRecoveryRecreatesTheViewNotPresentationChanges() {
        val recovery = mutableStateOf(0)
        val views = mutableSetOf<PlayerView>()
        var releases = 0
        compose.setContent {
            Box(Modifier.requiredSize(900.dp, 500.dp)) {
                PersistentLiveVideoLayer(Rect(0f, 0f, 900f, 500f), true, true, recovery.value, channel, {},
                    onCreated = { views += it }, onReleased = { releases++ })
            }
        }
        compose.runOnIdle { assertEquals(1, views.size); recovery.value++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, views.size); assertEquals(1, releases) }
    }

    @Test fun boundsAccountForOuterNavigationAndPortraitFullscreenWithoutChangingAspectPolicy() {
        val preview = Rect(320f, 70f, 520f, 182f)
        assertEquals(Rect(240f, 50f, 440f, 162f), liveVideoPlacement(IntSize(1000, 600), Offset(80f, 20f), preview, false))
        assertEquals(Rect(0f, 0f, 400f, 880f), liveVideoPlacement(IntSize(400, 880), Offset(0f, 24f), preview, true))
        assertEquals(Rect(0f, 0f, 1000f, 600f), liveVideoPlacement(IntSize(1000, 600), Offset.Zero, null, false))
        assertNull(liveVideoPlacement(IntSize.Zero, Offset.Zero, preview, true))
    }
}
