package com.arflix.tv.data.repository

import com.arflix.tv.data.model.StreamSource
import org.junit.Assert.*
import org.junit.Test

class LibrarySourcePreferenceTest {
    private val vod = StreamSource("The Beekeeper (2024)", "IPTV VOD", "iptv_xtream_vod", "1080p", "",
        url = "https://provider.example.test/movie.mp4")
    private val cloud = StreamSource("The.Beekeeper.2024.2160p", "Torrentio PM", "cloud-source", "4K", "52 GB",
        url = "https://cloud.example.test/movie.mp4", displayAddonName = "Extreme Cloud")
    @Test fun authorizedBackupMergeKeepsVodFirstAndBothLinksAvailable() {
        assertEquals(listOf(vod, cloud), mergeVodAndBackupSources(listOf(vod), listOf(cloud)))
    }
    @Test fun backupResponsesCannotDuplicateVodRows() {
        assertEquals(listOf(vod, cloud), mergeVodAndBackupSources(listOf(vod), listOf(vod, cloud)))
    }
    @Test fun unusedCloudLinksAreNotAutomaticallyPrewarmedWhenVodExists() {
        assertEquals(listOf(vod), automaticPrewarmSources(listOf(cloud, vod)))
    }
    @Test fun cloudOnlyResultsRetainExistingPrewarmPolicy() {
        assertEquals(listOf(cloud), automaticPrewarmSources(listOf(cloud)))
    }
    @Test fun personalLibraryAlsoAvoidsUnnecessaryCloudResolution() {
        val personal = vod.copy(addonId = HomeServerRepository.ADDON_ID, addonName = "Jellyfin")
        assertEquals(listOf(personal), automaticPrewarmSources(listOf(cloud, personal)))
    }
}
