package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvProgram
import org.junit.Assert.*
import org.junit.Test

class RetainedGuideWindowTest {
    private val programme = IptvProgram("Saved", startUtcMillis = 100, endUtcMillis = 200)

    @Test fun temporaryEmptyReadDoesNotBlankValidGuide() {
        val result = mergeRetainedGuideWindow(mapOf("a" to listOf(programme)), mapOf("a" to emptyList()), 50, 250, listOf("a"), 5)
        assertEquals(listOf(programme), result["a"])
    }
    @Test fun expiredRowsAreNotKeptAsLiveGuide() {
        assertTrue(mergeRetainedGuideWindow(mapOf("a" to listOf(programme)), emptyMap(), 300, 400, emptyList(), 5).isEmpty())
    }
    @Test fun visibleRowsSurviveBoundedEviction() {
        val result = mergeRetainedGuideWindow(mapOf("a" to listOf(programme), "b" to listOf(programme)),
            mapOf("c" to listOf(programme)), 50, 250, listOf("a"), 2)
        assertEquals(setOf("a", "c"), result.keys)
    }
    @Test fun scrollingBackReloadsPreviouslyRequestedButEvictedRows() {
        assertEquals(setOf("a"), unhydratedGuideChannelIds(setOf("a", "b"), setOf("a", "b"), setOf("b")))
    }
    @Test fun residentRowsAreNotRepeatedlyQueriedWithinSameWindow() {
        assertTrue(unhydratedGuideChannelIds(setOf("a"), setOf("a"), setOf("a")).isEmpty())
    }
    @Test fun partialRefreshCorrectsOneProgrammeWithoutErasingLoadedFutureListings() {
        val future = programme.copy(title = "Saved future", startUtcMillis = 210, endUtcMillis = 240)
        val corrected = programme.copy(title = "Updated", endUtcMillis = 205)
        val result = mergeRetainedGuideWindow(mapOf("a" to listOf(programme, future)),
            mapOf("a" to listOf(corrected)), 50, 250, listOf("a"), 5)
        assertEquals(listOf(corrected, future), result["a"])
    }
}
