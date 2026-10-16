package com.arflix.tv.ui.screens.tv.live

/**
 * Coalesces repeated edge-prefetch requests emitted while a remote key is held.
 * Focus still advances synchronously; only the expensive channel-window update
 * is rate-limited.
 */
internal class TvGuidePageRequestGate(
    private val minIntervalMs: Long = 180L,
) {
    private var previousAtMs = Long.MIN_VALUE
    private var previousDirection: TvGuideDirection? = null

    fun shouldRequest(direction: TvGuideDirection, nowMs: Long): Boolean {
        val accepted = direction != previousDirection || nowMs - previousAtMs >= minIntervalMs
        if (accepted) {
            previousDirection = direction
            previousAtMs = nowMs
        }
        return accepted
    }
}
