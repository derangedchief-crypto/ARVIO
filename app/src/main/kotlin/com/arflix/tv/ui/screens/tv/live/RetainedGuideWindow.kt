package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvProgram

internal fun unhydratedGuideChannelIds(wanted: Set<String>, requested: Set<String>, resident: Set<String>): Set<String> =
    wanted - requested.intersect(resident)

/** Keep still-valid cells across transient empty reads; do not retain expired rows or evicted IDs. */
internal fun mergeRetainedGuideWindow(
    previous: Map<String, List<IptvProgram>>,
    incoming: Map<String, List<IptvProgram>>,
    windowStart: Long,
    windowEnd: Long,
    visibleIds: List<String>,
    capacity: Int,
): Map<String, List<IptvProgram>> {
    val result = LinkedHashMap<String, List<IptvProgram>>()
    fun valid(rows: List<IptvProgram>) = rows.filter { it.endUtcMillis > windowStart && it.startUtcMillis < windowEnd }
    previous.forEach { (id, rows) -> valid(rows).takeIf { it.isNotEmpty() }?.let { result[id] = it } }
    incoming.forEach { (id, rows) -> valid(rows).takeIf { it.isNotEmpty() }?.let { fresh ->
        val starts = fresh.mapTo(hashSetOf()) { it.startUtcMillis }
        result[id] = (result[id].orEmpty().filterNot { it.startUtcMillis in starts } + fresh)
            .sortedBy { it.startUtcMillis }
    } }
    visibleIds.forEach { id -> result.remove(id)?.let { result[id] = it } }
    while (result.size > capacity.coerceAtLeast(visibleIds.size)) {
        val iterator = result.entries.iterator()
        iterator.next()
        iterator.remove()
    }
    return result
}
