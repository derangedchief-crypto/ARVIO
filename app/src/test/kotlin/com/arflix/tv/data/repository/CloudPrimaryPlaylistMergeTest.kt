package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class CloudPrimaryPlaylistMergeTest {
    private val primary = IptvPlaylistEntry("list_1", "Activated", "https://primary.test/list", "https://primary.test/epg")
    private fun merge(state: IptvCloudProfileState) = preserveCloudPrimary(state, primary) { it == primary.m3uUrl }

    @Test fun emptyBackupCannotRemoveActivation() {
        val merged = merge(IptvCloudProfileState())
        assertEquals(listOf(primary), merged.playlists)
        assertEquals(primary.m3uUrl, merged.m3uUrl)
        assertEquals(primary.epgUrl, merged.epgUrl)
    }

    @Test fun disabledRenamedPrimaryRetainsLocalIdAndRemapsViewingReferences() {
        val merged = merge(IptvCloudProfileState(
            playlists = listOf(primary.copy(id = "remote", enabled = false)),
            favoriteChannels = listOf("remote:xtream:42"),
            favoriteGroups = listOf("remote|News"),
            hiddenGroups = listOf("remote|Hidden"),
            groupOrder = listOf("News"),
            tvSession = IptvTvSessionState(lastChannelId = "remote:xtream:42", recentChannelIds = listOf("remote:xtream:42")),
        ))
        assertEquals(listOf(primary), merged.playlists)
        assertEquals(listOf("list_1:xtream:42"), merged.favoriteChannels)
        assertEquals(listOf("list_1|News"), merged.favoriteGroups)
        assertEquals(listOf("list_1|Hidden"), merged.hiddenGroups)
        assertEquals(listOf("list_1|News"), merged.groupOrder)
        assertEquals("list_1:xtream:42", merged.tvSession.lastChannelId)
        assertEquals(merged.favoriteChannels, merged.tvSession.recentChannelIds)
    }

    @Test fun differentAccountUsingSameSlotIsAnExtraNotAReplacement() {
        val extra = primary.copy(m3uUrl = "https://other.test/list")
        val merged = merge(IptvCloudProfileState(
            playlists = listOf(extra, extra.copy(id = "cloud_list_1")),
            favoriteChannels = listOf("list_1:xtream:42", "stalker:portal:7"),
            hiddenGroups = listOf("News"),
        ))
        assertEquals(primary, merged.playlists.first())
        assertEquals("cloud_list_1_1", merged.playlists[1].id)
        assertEquals(extra.m3uUrl, merged.playlists[1].m3uUrl)
        assertEquals(listOf("cloud_list_1_1:xtream:42", "stalker:portal:7"), merged.favoriteChannels)
        assertEquals(listOf("cloud_list_1_1|News"), merged.hiddenGroups)
        assertEquals(3, merged.playlists.map { it.id }.toSet().size)
    }

    @Test fun legacyBackupAndUnboundProfileRemainSupported() {
        assertEquals(listOf(primary), merge(IptvCloudProfileState(m3uUrl = primary.m3uUrl)).playlists)
        val cloud = IptvCloudProfileState(m3uUrl = "https://other.test/list")
        assertSame(cloud, preserveCloudPrimary(cloud, null) { false })
    }
}
