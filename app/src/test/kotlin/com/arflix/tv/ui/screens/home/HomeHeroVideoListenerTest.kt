package com.arflix.tv.ui.screens.home

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HomeHeroVideoListenerTest {
    @Test fun heroSurfaceUsesTextureViewSoUnrenderedVideoCannotCoverArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val view = android.view.LayoutInflater.from(context).inflate(com.arflix.tv.R.layout.trailer_player_view, null)
            as androidx.media3.ui.PlayerView
        assertTrue(view.videoSurfaceView is android.view.TextureView)
        assertFalse(view.useController)
    }

    @Test fun brokenIntroFallsBackToArtworkAndLateEventsCannotHideNextService() {
        val player = mockk<Player>()
        val netflix = "https://example.test/netflix.mp4"
        val hulu = "https://example.test/hulu.mp4"
        var requested: String? = netflix
        var current = netflix
        every { player.currentMediaItem } answers { MediaItem.fromUri(current) }
        var rendered: String? = null
        var failed: String? = null
        var endings = 0
        val listener = homeHeroVideoListener(player, { requested }, { rendered = it }, { endings++ }, { failed = it })
        assertFalse(homeHeroVideoHasFrame(requested, rendered))
        listener.onRenderedFirstFrame()
        assertTrue(homeHeroVideoHasFrame(requested, rendered))
        val error = PlaybackException("404", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        listener.onPlayerError(error)
        assertEquals(netflix, failed)
        requested = hulu
        listener.onRenderedFirstFrame()
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        listener.onPlayerError(error)
        assertEquals(0, endings)
        assertEquals(netflix, rendered)
        assertEquals(netflix, failed)
        assertFalse(homeHeroVideoHasFrame(requested, rendered))
        current = hulu
        listener.onRenderedFirstFrame()
        assertTrue(homeHeroVideoHasFrame(requested, rendered))
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, endings)
        requested = null
        listener.onRenderedFirstFrame()
        assertFalse(homeHeroVideoHasFrame(requested, rendered))
    }
}
