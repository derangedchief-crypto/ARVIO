package com.arflix.tv.ui.screens.tv.live

/** Normal clicks start now; only a rapid sequence needs codec-churn protection. */
internal fun liveTuneDebounceMs(previousRequestMs: Long?, nowMs: Long): Long =
    if (previousRequestMs != null && nowMs - previousRequestMs in 0L..650L) 250L else 0L

internal data class LiveTvBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int,
)

internal fun buildLiveTvBufferProfile(memoryClassMb: Int, isLowRamDevice: Boolean): LiveTvBufferProfile {
    val constrained = isLowRamDevice || memoryClassMb.coerceAtLeast(256) <= 384
    // Only lower the first-picture threshold. Keep steady-state depth, heap caps
    // and the larger recovery threshold that protect high-bitrate/unstable feeds.
    return LiveTvBufferProfile(
        minBufferMs = 20_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 750,
        bufferForPlaybackAfterRebufferMs = if (constrained) 3_500 else 4_000,
        targetBufferBytes = (if (constrained) 48 else 64) * 1024 * 1024,
        backBufferMs = 5_000,
    )
}
