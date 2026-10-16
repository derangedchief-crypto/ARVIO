package com.arflix.tv.data.repository

/** Keeps an app-open worker from immediately repeating a guide refresh that just completed. */
internal fun shouldReuseFreshBackgroundGuide(
    isBackgroundWork: Boolean,
    requestedForcedRefresh: Boolean,
    cachedEpgAtMs: Long,
    indexedProgramCount: Int,
    nowMs: Long,
    freshnessMs: Long = 6L * 60L * 60_000L,
): Boolean =
    isBackgroundWork &&
        requestedForcedRefresh &&
        cachedEpgAtMs > 0L &&
        nowMs - cachedEpgAtMs in 0 until freshnessMs &&
        indexedProgramCount > 0
