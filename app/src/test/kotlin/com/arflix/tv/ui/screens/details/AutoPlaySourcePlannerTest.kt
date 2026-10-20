package com.arflix.tv.ui.screens.details

import com.arflix.tv.data.model.StreamBehaviorHints
import com.arflix.tv.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlaySourcePlannerTest {
    @Test
    fun `best autoplay prefers 4k over small 720p even when sizeBytes hint is wrong`() {
        val small720 = stream(
            source = "Movie 720p WEB-DL",
            quality = "720p",
            size = "780 MB",
            sizeBytes = 100L * 1024L * 1024L * 1024L
        )
        val large4k = stream(
            source = "Movie 2160p 4K WEB-DL",
            quality = "Unknown",
            size = "18.4 GB",
            sizeBytes = 1L
        )

        val selected = bestAutoPlayStream(listOf(small720, large4k), minQualityScore = 0)

        assertEquals(large4k, selected)
    }

    @Test
    fun `best autoplay uses visible size string between same quality sources`() {
        val smaller4k = stream(source = "Movie 2160p", quality = "4K", size = "8 GB")
        val bigger4k = stream(source = "Movie 2160p REMUX", quality = "4K", size = "52 GB")

        val selected = bestAutoPlayStream(listOf(smaller4k, bigger4k), minQualityScore = 0)

        assertEquals(bigger4k, selected)
    }

    @Test
    fun `remote 4k source preserves the local-library grace window`() {
        val top = stream(source = "Movie 2160p REMUX", quality = "4K", size = "52 GB")

        // Still settling: hold briefly so a bigger 4K can arrive, even while addons load.
        assertTrue(
            shouldWaitForAutoPlaySources(
                isLoadingStreams = true,
                selectedStream = top,
                elapsedMs = AUTOPLAY_TOP_TIER_SETTLE_MS - 1
            )
        )
        // Remote sources must not bypass the existing local-library grace.
        assertTrue(
            shouldWaitForAutoPlaySources(
                isLoadingStreams = true,
                selectedStream = top,
                elapsedMs = AUTOPLAY_TOP_TIER_SETTLE_MS
            )
        )
        assertFalse(shouldWaitForAutoPlaySources(true, top,
            AUTOPLAY_MAX_WAIT_MS + AUTOPLAY_LOCAL_PROBE_EXTRA_MS))
    }

    @Test
    fun `finished addons do not bypass the local-library grace window`() {
        val weak = stream(source = "Movie 720p WEB-DL", quality = "720p", size = "780 MB")

        // Addons still loading → keep collecting in case a better source arrives.
        assertTrue(
            shouldWaitForAutoPlaySources(
                isLoadingStreams = true,
                selectedStream = weak,
                elapsedMs = 100
            )
        )
        // Home-server/library probes are independent of addon completion.
        assertTrue(
            shouldWaitForAutoPlaySources(
                isLoadingStreams = false,
                selectedStream = weak,
                elapsedMs = 100
            )
        )
        assertFalse(shouldWaitForAutoPlaySources(false, weak,
            AUTOPLAY_MAX_WAIT_MS + AUTOPLAY_LOCAL_PROBE_EXTRA_MS))
    }

    @Test
    fun `remote autoplay stops waiting at the combined local-probe ceiling`() {
        val weak = stream(source = "Movie 720p WEB-DL", quality = "720p", size = "780 MB")

        assertTrue(
            shouldWaitForAutoPlaySources(
                isLoadingStreams = true,
                selectedStream = weak,
                elapsedMs = AUTOPLAY_MAX_WAIT_MS
            )
        )
        assertFalse(shouldWaitForAutoPlaySources(true, weak,
            AUTOPLAY_MAX_WAIT_MS + AUTOPLAY_LOCAL_PROBE_EXTRA_MS))
    }

    @Test
    fun `verified vod sources do not wait for remote scraper results`() {
        val local = stream("Movie 1080p", "1080p", "4 GB")
            .copy(addonId = "iptv_xtream_vod", addonName = "IPTV VOD")
        assertFalse(shouldWaitForAutoPlaySources(false, local, 0L))
    }
    @Test fun `vod remains preferred even when higher quality Extreme Cloud backups are available`() {
        val vod = stream("The Beekeeper (2024)", "1080p", "2 GB").copy(addonId = "iptv_xtream_vod", addonName = "IPTV VOD")
        val cloud = stream("The.Beekeeper.2024.2160p.REMUX", "4K", "52 GB").copy(displayAddonName = "Extreme Cloud")
        assertEquals(vod, bestAutoPlayStream(listOf(cloud, vod), minQualityScore = 4))
    }

    @Test
    fun `autoplay waits for the first source while addons load, then gives up at ceiling`() {
        assertTrue(
            shouldWaitForAutoPlaySources(isLoadingStreams = true, selectedStream = null, elapsedMs = 100)
        )
        assertFalse(
            shouldWaitForAutoPlaySources(isLoadingStreams = false, selectedStream = null, elapsedMs = 100)
        )
    }

    @Test
    fun `best autoplay accepts a notWebReady 4k over a webReady 1080p`() {
        val webReady1080 = stream(
            source = "Movie 1080p WEB-DL",
            quality = "1080p",
            size = "4 GB",
            notWebReady = false
        )
        val notWebReady4k = stream(
            source = "Movie 2160p MKV",
            quality = "4K",
            size = "20 GB",
            notWebReady = true
        )

        val selected = bestAutoPlayStream(listOf(webReady1080, notWebReady4k), minQualityScore = 0)

        assertEquals(notWebReady4k, selected)
    }

    private fun stream(
        source: String,
        quality: String,
        size: String,
        sizeBytes: Long? = null,
        cached: Boolean = true,
        notWebReady: Boolean = false
    ) = StreamSource(
        source = source,
        addonName = "Torrentio",
        addonId = "torrentio",
        quality = quality,
        size = size,
        sizeBytes = sizeBytes,
        url = "https://example.com/${source.hashCode()}",
        behaviorHints = StreamBehaviorHints(cached = cached, notWebReady = notWebReady)
    )
}
