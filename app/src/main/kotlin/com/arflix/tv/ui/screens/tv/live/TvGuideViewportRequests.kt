package com.arflix.tv.ui.screens.tv.live

/**
 * Drops late database results when the user has already moved to another
 * category, page, or guide time bucket. Query cancellation is best-effort on
 * Android SQLite; this gate makes stale delivery impossible even when a cursor
 * finishes after cancellation.
 */
internal class TvGuideViewportRequestGate {
    private var latestRequestId = 0L

    fun beginRequest(): Long = ++latestRequestId

    fun isCurrent(requestId: Long): Boolean = requestId == latestRequestId
}
