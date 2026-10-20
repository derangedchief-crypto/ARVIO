package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class ServiceIntroAssetsTest {
    private val old = "https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/"

    @Test fun storedIntroAndArtworkUrlsMigrateWithoutClearingProfiles() {
        listOf("networks%20videos/netflix.mp4", "networks%20collection/netflix.png").forEach { path ->
            assertEquals(ServiceIntroAssets.BASE_URL + path, ServiceIntroAssets.resolve(old + path))
        }
    }
    @Test fun customVideoUrlsAndOtherHostsAreNotChanged() {
        listOf("https://my.example/intro.mp4", "https://example.com/?url=$old", ServiceIntroAssets.BASE_URL + "networks%20videos/hulu.mp4")
            .forEach { assertEquals(it, ServiceIntroAssets.resolve(it)) }
        assertNull(ServiceIntroAssets.resolve(null))
        assertNull(ServiceIntroAssets.resolve("  "))
    }
    @Test fun allBundledServiceIntrosUseTheWorkingCopy() {
        val videos = MediaRepository.buildPreinstalledDefaults().filter { it.id.startsWith("collection_service_") }
            .mapNotNull { it.collectionHeroVideoUrl }
        assertEquals(8, videos.size)
        assertTrue(videos.all { it.startsWith(ServiceIntroAssets.BASE_URL) })
    }
}
