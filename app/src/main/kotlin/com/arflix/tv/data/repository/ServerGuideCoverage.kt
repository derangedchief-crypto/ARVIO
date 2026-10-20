package com.arflix.tv.data.repository

/** Download freshness is not programme freshness. Ignore never-published feeds,
 * but recover in bulk when most known, matched feeds have already expired. */
internal fun needsServerGuideBulkRecovery(currentChannels: Int, expiredChannels: Int): Boolean =
    expiredChannels >= 8 && expiredChannels > currentChannels
