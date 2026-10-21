// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from OwnTV EpgScreen.kt, commit cead977803d90bd62ece69b4140e373f01feddca.
package com.arflix.tv.ui.screens.tv.live

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Left browses future listings back to live; null means return to channel names.
 * Never enter history implicitly, including gaps and rows with different spans. */
internal fun ownTvGuideLeftStep(
    programmes: List<EpgProgrammeEntity>, cursor: Long, now: Long, windowStart: Long, windowEnd: Long,
): Long? {
    require(windowEnd > windowStart)
    val liveCursor = now.coerceIn(windowStart, windowEnd - 1)
    if (cursor <= liveCursor || programmes.any {
        cursor >= it.startMs && cursor < it.stopMs && now >= it.startMs && now < it.stopMs
    }) return null
    return ownTvGuideCursorStep(programmes, cursor, -1, windowStart, windowEnd).coerceAtLeast(liveCursor)
}

/** Programme cursor shared by rows: vertical navigation preserves the selected wall-clock time. */
internal fun ownTvGuideCursorStep(
    programmes: List<EpgProgrammeEntity>,
    cursor: Long,
    direction: Int,
    windowStart: Long,
    windowEnd: Long,
): Long {
    require(windowEnd > windowStart)
    val gapStep = 30L * 60_000L
    val current = programmes.firstOrNull { cursor >= it.startMs && cursor < it.stopMs }
    val target = if (direction > 0) {
        val from = current?.stopMs ?: cursor
        val next = programmes.firstOrNull { it.startMs >= from && it.startMs > cursor }
        when {
            current != null && (next == null || next.startMs - current.stopMs > gapStep) -> current.stopMs
            current != null -> next!!.startMs
            next != null && next.startMs - cursor <= gapStep -> next.startMs
            else -> cursor + gapStep
        }
    } else {
        val until = current?.startMs ?: cursor
        val previous = programmes.lastOrNull { it.stopMs <= until }
        when {
            current != null && (previous == null || current.startMs - previous.stopMs > gapStep) -> current.startMs - gapStep
            current != null -> previous!!.startMs
            previous != null && cursor - previous.stopMs <= gapStep -> previous.startMs
            else -> cursor - gapStep
        }
    }
    return target.coerceIn(windowStart, windowEnd - 1)
}
