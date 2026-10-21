package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class VerifiedVodCatalogsTest {
    @Test fun requiresSuccessfulExactGenerationAndAccountWithinFreshnessWindow() {
        val cache = VerifiedVodCatalogs(freshMs = 1000)
        assertFalse(cache.isFresh("account/vod", 100, 200))
        cache.mark("account/vod", 100)
        assertTrue(cache.isFresh("account/vod", 100, 200))
        assertFalse(cache.isFresh("other/vod", 100, 200))
        assertFalse(cache.isFresh("account/series", 100, 200))
        assertFalse(cache.isFresh("account/vod", 101, 200))
        assertFalse(cache.isFresh("account/vod", 100, 1100))
        assertFalse(cache.isFresh("account/vod", 100, 99))
    }
}
