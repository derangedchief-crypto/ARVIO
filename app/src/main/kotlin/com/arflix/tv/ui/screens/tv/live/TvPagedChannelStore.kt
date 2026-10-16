package com.arflix.tv.ui.screens.tv.live

/**
 * The SQLite store must take over whenever it contains more channels than the
 * bounded startup snapshot—not only for exceptionally huge providers.
 */
internal fun shouldUsePagedChannelStore(snapshotChannelCount: Int, indexedChannelCount: Int): Boolean =
    indexedChannelCount > snapshotChannelCount.coerceAtLeast(GuideMaxWindowRows)
