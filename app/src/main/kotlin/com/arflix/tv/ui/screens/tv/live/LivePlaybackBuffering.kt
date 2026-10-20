package com.arflix.tv.ui.screens.tv.live

/** The active engine is authoritative; a stale prepare flag must not cover playing video. */
internal fun livePlaybackBuffering(
    ownTvActive: Boolean,
    ownTvBuffering: Boolean,
    media3Buffering: Boolean,
    ownTvHasError: Boolean = false,
): Boolean = if (ownTvActive) ownTvBuffering && !ownTvHasError else media3Buffering
