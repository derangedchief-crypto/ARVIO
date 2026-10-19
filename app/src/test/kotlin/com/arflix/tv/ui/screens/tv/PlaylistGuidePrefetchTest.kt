package com.arflix.tv.ui.screens.tv

import com.arflix.tv.data.repository.prefetchPlaylistGuide

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaylistGuidePrefetchTest {
    @Test fun largePlaylistIsNotCappedToVisibleOrPriorityChannels() = runTest {
        var fetched = 0
        var last = ""
        prefetchPlaylistGuide(10_025, 12,
            { offset, limit -> (offset until offset + limit).map { "channel-$it" } },
            { emptySet() }, { batch -> fetched += batch.size; last = batch.last(); batch },
            { _, _, _ -> }, { true }, {})
        assertEquals(10_025, fetched)
        assertEquals("channel-10024", last)
    }
    @Test fun everyUnselectedChannelIsVisitedInBoundedPages() = runTest {
        val ids = (0 until 101).map { "channel-$it" }
        val fetched = mutableSetOf<String>()
        val batchSizes = mutableListOf<Int>()
        var visited = 0
        prefetchPlaylistGuide(ids.size, 12, { offset, limit -> ids.drop(offset).take(limit) },
            { emptySet() }, { batch -> batchSizes += batch.size; fetched += batch; batch },
            { count, _, _ -> visited = count }, { true }, {})
        assertEquals(ids.toSet(), fetched)
        assertEquals(ids.size, visited)
        assertTrue(batchSizes.all { it <= 12 })
    }
    @Test fun persistedChannelsDoNotTriggerNetworkDownloadsAgain() = runTest {
        var fetched = emptySet<String>()
        prefetchPlaylistGuide(3, 12, { _, _ -> listOf("cached", "missing", "also-cached") },
            { setOf("cached", "also-cached") }, { fetched = it; it }, { _, _, _ -> }, { true }, {})
        assertEquals(setOf("missing"), fetched)
    }
    @Test fun oneEmptyProviderResponseDoesNotStopLaterChannels() = runTest {
        val ids = listOf("no-listings", "has-listings")
        val attempted = mutableListOf<String>()
        prefetchPlaylistGuide(2, 1, { offset, limit -> ids.drop(offset).take(limit) }, { emptySet() },
            { attempted += it; if ("no-listings" in it) emptySet() else it }, { _, _, _ -> }, { true }, {})
        assertEquals(ids, attempted)
    }
    @Test fun providerFailureDoesNotDiscardTheRemainingPlaylist() = runTest {
        val attempted = mutableListOf<String>()
        prefetchPlaylistGuide(2, 1, { offset, _ -> listOf("$offset") }, { emptySet() },
            { attempted += it; if ("0" in it) error("Temporary provider failure") else it },
            { _, _, _ -> }, { true }, {})
        assertEquals(listOf("0", "1"), attempted)
    }
    @Test fun sourceChangeStopsBeforeRequestingAnotherPage() = runTest {
        var active = true
        var calls = 0
        prefetchPlaylistGuide(100, 12, { offset, limit -> (offset until offset + limit).map { "$it" } },
            { emptySet() }, { calls++; it }, { _, _, _ -> active = false }, { active }, {})
        assertEquals(1, calls)
    }
    @Test fun cancellationIsNotMistakenForAnEmptyProviderGuide() = runTest {
        try {
            prefetchPlaylistGuide(1, 1, { _, _ -> listOf("channel") }, { emptySet() },
                { throw CancellationException("Leaving TV") }, { _, _, _ -> fail() }, { true }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
