package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram

/**
 * Immutable, bounded input for the Live TV renderer.
 *
 * The renderer never needs the provider's full guide. Each row gets only the
 * programmes which overlap its displayed time window, retaining stable program
 * identities so a database revision cannot blank an already rendered row.
 */
internal data class TvGuideViewportModel(
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val rows: Map<String, List<IptvProgram>>,
) {
    fun programsFor(channelId: String): List<IptvProgram> = rows[channelId].orEmpty()
}

internal fun buildTvGuideViewportModel(
    channelIds: Collection<String>,
    nowNext: Map<String, IptvNowNext>,
    windowStartMillis: Long,
    windowEndMillis: Long,
): TvGuideViewportModel {
    require(windowEndMillis > windowStartMillis)
    val rows = LinkedHashMap<String, List<IptvProgram>>(channelIds.size)
    channelIds.forEach { channelId ->
        val item = nowNext[channelId] ?: return@forEach
        val programs = sequenceOf(
            item.recent.asSequence(),
            sequenceOf(item.now, item.next, item.later).filterNotNull(),
            item.upcoming.asSequence(),
        )
            .flatten()
            .filter { it.endUtcMillis > windowStartMillis && it.startUtcMillis < windowEndMillis }
            .distinctBy { Triple(it.startUtcMillis, it.endUtcMillis, it.title) }
            .sortedBy { it.startUtcMillis }
            .toList()
        if (programs.isNotEmpty()) rows[channelId] = programs
    }
    return TvGuideViewportModel(windowStartMillis, windowEndMillis, rows)
}
