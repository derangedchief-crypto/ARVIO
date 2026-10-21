package com.arflix.tv.ui.screens.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.EpgDedupe

class OwnTvGuideNavigationTest {
    private val minute = 60_000L
    private fun programme(start: Int, end: Int, title: String = "Programme") =
        EpgProgrammeEntity(sourceId = 0, epgChannelId = "channel", startMs = start * minute,
            stopMs = end * minute, title = title)

    @Test fun leftReturnsThroughFutureCellsToLiveBeforeExiting() {
        val rows = listOf(programme(0, 60), programme(60, 90), programme(90, 120))
        val now = 15 * minute
        assertEquals(60 * minute, ownTvGuideLeftStep(rows, 90 * minute, now, 0, 240 * minute))
        assertEquals(now, ownTvGuideLeftStep(rows, 60 * minute, now, 0, 240 * minute))
        assertNull(ownTvGuideLeftStep(rows, now, now, 0, 240 * minute))
    }

    @Test fun selectedLiveProgrammeExitsEvenWhenItsCursorIsLaterThanNow() {
        assertNull(ownTvGuideLeftStep(listOf(programme(0, 120)), 90 * minute, 15 * minute, 0, 240 * minute))
    }

    @Test fun emptyOrGappedRowsClampAtNowRatherThanSteppingIntoHistory() {
        val now = 15 * minute
        assertEquals(now, ownTvGuideLeftStep(emptyList(), 30 * minute, now, 0, 240 * minute))
        assertNull(ownTvGuideLeftStep(emptyList(), now, now, 0, 240 * minute))
        assertEquals(90 * minute, ownTvGuideLeftStep(listOf(programme(0, 30)), 100 * minute, 90 * minute, 0, 240 * minute))
    }

    @Test fun explicitlySelectedHistoryStillExitsLeftWithoutTrappingFocus() {
        assertNull(ownTvGuideLeftStep(listOf(programme(0, 60), programme(60, 120)), 0, 75 * minute, 0, 240 * minute))
    }

    @Test fun rightMovesToNextProgramme() {
        val rows = listOf(programme(0, 60), programme(60, 90))
        assertEquals(60 * minute, ownTvGuideCursorStep(rows, 15 * minute, 1, 0, 180 * minute))
    }

    @Test fun leftMovesToPreviousProgramme() {
        val rows = listOf(programme(0, 60), programme(60, 90))
        assertEquals(0L, ownTvGuideCursorStep(rows, 75 * minute, -1, 0, 180 * minute))
    }

    @Test fun longGapDoesNotJumpAcrossHours() {
        val rows = listOf(programme(0, 60), programme(180, 240))
        assertEquals(60 * minute, ownTvGuideCursorStep(rows, 30 * minute, 1, 0, 300 * minute))
        assertEquals(90 * minute, ownTvGuideCursorStep(rows, 60 * minute, 1, 0, 300 * minute))
    }

    @Test fun emptyRowMovesByHalfHourAndClampsAtBounds() {
        assertEquals(30 * minute, ownTvGuideCursorStep(emptyList(), 0, 1, 0, 60 * minute))
        assertEquals(0L, ownTvGuideCursorStep(emptyList(), 0, -1, 0, 60 * minute))
        assertEquals(60 * minute - 1, ownTvGuideCursorStep(emptyList(), 59 * minute, 1, 0, 60 * minute))
    }

    @Test fun ownTvDedupeKeepsWiderOverlappingBroadcastButNotRepeat() {
        val rows = listOf(programme(0, 60, "Film"), programme(5, 55, " FILM "), programme(60, 120, "Film"))
        assertEquals(listOf(rows[0], rows[2]), EpgDedupe.collapse(rows))
    }
}
