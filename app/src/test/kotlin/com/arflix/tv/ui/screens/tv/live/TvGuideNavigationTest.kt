package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvGuideNavigationTest {
    private val programs: (Int) -> Int = { row -> if (row == 1) 3 else 1 }

    @Test
    fun movesAcrossRowsWithoutDataWork() {
        val result = TvGuideNavigation.move(
            TvGuideCursor(rowIndex = 1), TvGuideDirection.DOWN, rowCount = 4, programCountForRow = programs,
        )

        assertThat(result.cursor.rowIndex).isEqualTo(2)
        assertThat(result.requestNextPage).isFalse()
    }

    @Test
    fun requestsNextPageOnlyAtViewportBoundary() {
        val result = TvGuideNavigation.move(
            TvGuideCursor(rowIndex = 3), TvGuideDirection.DOWN, rowCount = 4, programCountForRow = programs,
        )

        assertThat(result.cursor.rowIndex).isEqualTo(3)
        assertThat(result.requestNextPage).isTrue()
    }

    @Test
    fun preservesProgramLaneAndTimeAnchorWhenChangingRows() {
        val result = TvGuideNavigation.move(
            TvGuideCursor(
                rowIndex = 1,
                lane = TvGuideFocusLane.PROGRAMS,
                timeAnchorMinutes = 135,
            ),
            TvGuideDirection.DOWN,
            rowCount = 4,
            programCountForRow = programs,
        )

        assertThat(result.cursor.rowIndex).isEqualTo(2)
        assertThat(result.cursor.lane).isEqualTo(TvGuideFocusLane.PROGRAMS)
        assertThat(result.cursor.timeAnchorMinutes).isEqualTo(135)
    }

    @Test
    fun switchesBetweenChannelAndProgramLanes() {
        val entered = TvGuideNavigation.move(
            TvGuideCursor(rowIndex = 1), TvGuideDirection.RIGHT, rowCount = 4, programCountForRow = programs,
        )
        val exited = TvGuideNavigation.move(
            entered.cursor, TvGuideDirection.LEFT, rowCount = 4, programCountForRow = programs,
        )

        assertThat(entered.cursor.lane).isEqualTo(TvGuideFocusLane.PROGRAMS)
        assertThat(exited.cursor.lane).isEqualTo(TvGuideFocusLane.CHANNELS)
    }
}
