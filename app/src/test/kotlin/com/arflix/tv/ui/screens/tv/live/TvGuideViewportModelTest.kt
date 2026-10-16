package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvGuideViewportModelTest {
    @Test
    fun keepsOnlyProgrammesThatIntersectViewportAndRemovesDuplicates() {
        val before = IptvProgram("Before", null, 0L, 100L)
        val overlap = IptvProgram("Overlap", null, 50L, 150L)
        val after = IptvProgram("After", null, 150L, 200L)
        val model = buildTvGuideViewportModel(
            channelIds = listOf("one"),
            indexedPrograms = mapOf("one" to listOf(before, overlap, overlap, after)),
            fallbackNowNext = emptyMap(),
            windowStartMillis = 100L,
            windowEndMillis = 160L,
        )

        assertThat(model.programsFor("one")).containsExactly(overlap, after).inOrder()
    }

    @Test
    fun leavesUnmatchedChannelsEmptyInsteadOfBorrowingProgrammeData() {
        val model = buildTvGuideViewportModel(
            channelIds = listOf("one", "two"),
            indexedPrograms = emptyMap(),
            fallbackNowNext = mapOf("one" to IptvNowNext(now = IptvProgram("One", null, 0L, 100L))),
            windowStartMillis = 0L,
            windowEndMillis = 200L,
        )

        assertThat(model.programsFor("two")).isEmpty()
    }
}
