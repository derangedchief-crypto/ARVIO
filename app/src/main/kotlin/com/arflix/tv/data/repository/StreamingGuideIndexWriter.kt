package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram

/** Row-bounded disk sink shared by the full-feed importer and its parser regression tests. */
internal class StreamingGuideIndexWriter(
    private val capacity: Int = 256,
    private val write: (Map<String, IptvNowNext>) -> Unit,
) {
    init { require(capacity > 0) }
    private val batch = LinkedHashMap<String, MutableList<IptvProgram>>()
    private var rows = 0

    fun add(channelId: String, programme: IptvProgram): Boolean {
        batch.getOrPut(channelId) { mutableListOf() }.add(programme)
        rows++
        if (rows < capacity) return false
        flush()
        return true
    }

    fun flush() {
        if (rows == 0) return
        write(batch.mapValues { IptvNowNext(upcoming = it.value.toList()) })
        batch.clear()
        rows = 0
    }
}
