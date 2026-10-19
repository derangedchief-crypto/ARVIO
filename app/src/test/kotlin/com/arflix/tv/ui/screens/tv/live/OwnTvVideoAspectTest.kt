package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class)
class OwnTvVideoAspectTest {
    private fun surfaceAspect(width: Int, height: Int, sourceAspect: Float?): Float {
        val view = PlayerView(RuntimeEnvironment.getApplication())
        // mpv owns this view: no ExoPlayer exists to deliver a video-size event.
        assertNull(view.player)
        fitOwnTvVideo(view, sourceAspect)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
        val surface = view.videoSurfaceView!!
        return surface.width.toFloat() / surface.height
    }
    @Test fun wideFoldContainerDoesNotStretchWidescreenVideo() {
        assertEquals(16f / 9f, surfaceAspect(800, 300, 16f / 9f), 0.015f)
    }
    @Test fun portraitContainerFitsVideoWithoutSqueezingIt() {
        assertEquals(16f / 9f, surfaceAspect(400, 650, 16f / 9f), 0.015f)
    }
    @Test fun fourByThreeStreamKeepsItsOwnShape() {
        assertEquals(4f / 3f, surfaceAspect(800, 450, 4f / 3f), 0.015f)
    }
}
