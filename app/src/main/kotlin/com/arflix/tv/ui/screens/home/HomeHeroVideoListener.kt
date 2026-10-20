package com.arflix.tv.ui.screens.home

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/** Ignore late events from the previous focused service while its media is replaced. */
internal fun homeHeroVideoListener(
    player: Player,
    requestedUrl: () -> String?,
    onFirstFrame: (String) -> Unit,
    onEnded: () -> Unit,
    onFailed: (String) -> Unit,
): Player.Listener = object : Player.Listener {
    private fun activeUrl(): String? = requestedUrl()?.takeIf {
        it == player.currentMediaItem?.localConfiguration?.uri?.toString()
    }

    override fun onRenderedFirstFrame() { activeUrl()?.let(onFirstFrame) }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && activeUrl() != null) onEnded()
    }
    override fun onPlayerError(error: PlaybackException) { activeUrl()?.let(onFailed) }
}

internal fun homeHeroVideoHasFrame(requestedUrl: String?, renderedUrl: String?): Boolean =
    requestedUrl != null && requestedUrl == renderedUrl
