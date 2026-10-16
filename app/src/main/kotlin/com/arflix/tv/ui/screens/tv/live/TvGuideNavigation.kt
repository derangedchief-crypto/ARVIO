package com.arflix.tv.ui.screens.tv.live

/**
 * Pure D-pad navigation policy for the TV guide. It deliberately contains no
 * Compose focus requests or data loading: focus movement stays instant while
 * the renderer separately fulfils a narrow viewport request.
 */
internal enum class TvGuideFocusLane { CHANNELS, PROGRAMS }

internal enum class TvGuideDirection { UP, DOWN, LEFT, RIGHT }

internal data class TvGuideCursor(
    val rowIndex: Int,
    val programIndex: Int = 0,
    val lane: TvGuideFocusLane = TvGuideFocusLane.CHANNELS,
    val timeAnchorMinutes: Int = 0,
)

internal data class TvGuideNavigationResult(
    val cursor: TvGuideCursor,
    val requestPreviousPage: Boolean = false,
    val requestNextPage: Boolean = false,
)

internal object TvGuideNavigation {
    fun move(
        cursor: TvGuideCursor,
        direction: TvGuideDirection,
        rowCount: Int,
        programCountForRow: (Int) -> Int,
    ): TvGuideNavigationResult {
        if (rowCount <= 0) return TvGuideNavigationResult(cursor)
        val row = cursor.rowIndex.coerceIn(0, rowCount - 1)
        return when (direction) {
            TvGuideDirection.UP -> when {
                row > 0 -> TvGuideNavigationResult(cursor.copy(rowIndex = row - 1))
                else -> TvGuideNavigationResult(cursor.copy(rowIndex = 0), requestPreviousPage = true)
            }
            TvGuideDirection.DOWN -> when {
                row < rowCount - 1 -> TvGuideNavigationResult(cursor.copy(rowIndex = row + 1))
                else -> TvGuideNavigationResult(cursor.copy(rowIndex = rowCount - 1), requestNextPage = true)
            }
            TvGuideDirection.LEFT -> if (cursor.lane == TvGuideFocusLane.PROGRAMS && cursor.programIndex <= 0) {
                TvGuideNavigationResult(cursor.copy(rowIndex = row, lane = TvGuideFocusLane.CHANNELS, programIndex = 0))
            } else if (cursor.lane == TvGuideFocusLane.PROGRAMS) {
                TvGuideNavigationResult(cursor.copy(rowIndex = row, programIndex = cursor.programIndex - 1))
            } else {
                TvGuideNavigationResult(cursor.copy(rowIndex = row))
            }
            TvGuideDirection.RIGHT -> if (cursor.lane == TvGuideFocusLane.CHANNELS && programCountForRow(row) > 0) {
                TvGuideNavigationResult(cursor.copy(rowIndex = row, lane = TvGuideFocusLane.PROGRAMS, programIndex = 0))
            } else if (cursor.lane == TvGuideFocusLane.PROGRAMS && cursor.programIndex < programCountForRow(row) - 1) {
                TvGuideNavigationResult(cursor.copy(rowIndex = row, programIndex = cursor.programIndex + 1))
            } else {
                TvGuideNavigationResult(cursor.copy(rowIndex = row))
            }
        }
    }
}
