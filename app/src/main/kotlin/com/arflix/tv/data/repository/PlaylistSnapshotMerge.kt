package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel

/** Replace only confirmed source snapshots; a failed source must never disappear. */
internal fun mergePlaylistSnapshots(
    previous: List<IptvChannel>,
    replacements: Map<String, List<IptvChannel>>,
): List<IptvChannel> {
    fun prefix(channel: IptvChannel): String = if (channel.id.startsWith("stalker:"))
        channel.id.split(':').take(2).joinToString(":") + ":" else channel.id.substringBefore(':') + ":"
    val result = mutableListOf<IptvChannel>()
    val inserted = mutableSetOf<String>()
    previous.forEach { channel ->
        val source = prefix(channel)
        if (source !in replacements) result.add(channel)
        else if (inserted.add(source)) result.addAll(replacements.getValue(source))
    }
    replacements.forEach { (source, rows) -> if (inserted.add(source)) result.addAll(rows) }
    return result.distinctBy { it.id }
}
