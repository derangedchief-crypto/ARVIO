// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from OwnTV EpgScreen.kt, commit cead977803d90bd62ece69b4140e373f01feddca.
package com.arflix.tv.ui.screens.tv.live

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

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
