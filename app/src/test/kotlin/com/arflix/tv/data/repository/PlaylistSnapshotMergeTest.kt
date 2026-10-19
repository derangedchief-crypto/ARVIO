package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel
import org.junit.Assert.*
import org.junit.Test

class PlaylistSnapshotMergeTest {
    private fun channel(id: String) = IptvChannel(id, id, "https://example.invalid/$id", "News")
    @Test fun additionsAndRemovalsApplyWithoutDroppingFailedProviders() {
        val previous = listOf(channel("a:1"), channel("a:2"), channel("b:3"))
        val result = mergePlaylistSnapshots(previous, mapOf("a:" to listOf(channel("a:1"), channel("a:4"))))
        assertEquals(listOf("a:1", "a:4", "b:3"), result.map { it.id })
    }
    @Test fun unchangedSnapshotsKeepTheirOrder() {
        val previous = listOf(channel("a:1"), channel("b:2"))
        assertEquals(previous, mergePlaylistSnapshots(previous, mapOf("a:" to listOf(channel("a:1")))))
    }
    @Test fun stalkerReplacementDoesNotRemoveAnotherPortal() {
        val previous = listOf(channel("stalker:a:1"), channel("stalker:b:2"))
        assertEquals(listOf("stalker:a:3", "stalker:b:2"), mergePlaylistSnapshots(previous,
            mapOf("stalker:a:" to listOf(channel("stalker:a:3")))).map { it.id })
    }
}
