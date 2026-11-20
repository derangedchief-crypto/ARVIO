package com.arflix.tv.ui.screens.tv.live

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class PhoneLivePictureInPictureTest {
    @get:Rule val compose = createComposeRule()

    class Host : ComponentActivity() {
        var entries = 0
        var params: PictureInPictureParams? = null
        override fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
            entries++
            this.params = params
            return true
        }
        override fun setPictureInPictureParams(params: PictureInPictureParams) { this.params = params }
        fun leave() { super.onUserLeaveHint() }
        fun mode(active: Boolean) { super.onPictureInPictureModeChanged(active, Configuration()) }
    }

    @Test fun homeEntersOnlyWhileEnabledAndListenersAreRemovedOnNavigation() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val host = controller.get()
        shadowOf(host.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val enabled = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        var pip = false
        compose.setContent {
            if (mounted.value) PhoneLivePictureInPicture(host, enabled.value, null) { pip = it }
        }
        compose.runOnIdle {
            host.leave()
            assertEquals(1, host.entries)
            host.mode(true)
            assertTrue(pip)
            host.mode(false)
            assertFalse(pip)
            enabled.value = false
        }
        compose.runOnIdle { host.leave(); assertEquals(1, host.entries); mounted.value = false }
        compose.runOnIdle { host.leave(); assertEquals(1, host.entries) }
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [31]) fun android12UsesAutoEntryAndClearsItOnDisposal() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val host = controller.get()
        shadowOf(host.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val mounted = mutableStateOf(true)
        compose.setContent { if (mounted.value) PhoneLivePictureInPicture(host, true, null) {} }
        compose.runOnIdle {
            assertTrue(host.params!!.isAutoEnterEnabled)
            host.leave()
            assertEquals(0, host.entries)
            mounted.value = false
        }
        compose.runOnIdle { assertFalse(host.params!!.isAutoEnterEnabled) }
        controller.pause().stop().destroy()
    }

    // Public PiP parameter getters were added in API 33.
    @Test @Config(sdk = [33]) fun noPipWithoutPlaybackPermissionOrOnTv() {
        assertTrue(phoneLivePipAllowed(true, true, true, true, true))
        assertFalse(phoneLivePipAllowed(false, true, true, true, true))
        assertFalse(phoneLivePipAllowed(true, false, true, true, true))
        assertFalse(phoneLivePipAllowed(true, true, false, true, true))
        assertFalse(phoneLivePipAllowed(true, true, true, false, true))
        assertFalse(phoneLivePipAllowed(true, true, true, true, false))
        val params = livePipParams(false, Rect(10, 20, 330, 200))
        assertEquals(android.util.Rational(16, 9), params.aspectRatio)
        assertEquals(Rect(10, 20, 330, 200), params.sourceRectHint)
    }
}
