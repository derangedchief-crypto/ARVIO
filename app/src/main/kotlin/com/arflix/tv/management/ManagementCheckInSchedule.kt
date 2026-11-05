package com.arflix.tv.management

/** No input-idle mode while STARTED: watching without pressing buttons is active use. */
internal const val MANAGEMENT_VISIBLE_ACTIVITY = "active"
internal const val MANAGEMENT_MIN_CHECK_IN_MS = 4_000L
internal fun managementCheckInDelay(serverSeconds: Int, live: Boolean): Long =
    serverSeconds.coerceIn(15, if (live) 30 else 15) * 1000L

internal fun managementCheckInRemaining(now: Long, lastStart: Long): Long =
    (MANAGEMENT_MIN_CHECK_IN_MS - (now - lastStart)).coerceIn(0, MANAGEMENT_MIN_CHECK_IN_MS)
