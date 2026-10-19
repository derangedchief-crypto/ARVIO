package com.arflix.tv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One bounded page in memory; no selected-channel dependency and no playlist-wide result map. */
internal suspend fun prefetchPlaylistGuide(
    total: Int,
    pageSize: Int,
    loadPage: suspend (offset: Int, limit: Int) -> List<String>,
    cachedIds: suspend (Set<String>) -> Set<String>,
    fetchMissing: suspend (Set<String>) -> Set<String>,
    onPage: (visited: Int, attempted: Set<String>, populated: Set<String>) -> Unit,
    shouldContinue: () -> Boolean,
    betweenPages: suspend () -> Unit,
) {
    require(pageSize > 0)
    var offset = 0
    while (offset < total && shouldContinue()) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(offset, minOf(pageSize, total - offset))
        if (page.isEmpty()) break
        offset += page.size
        val ids = page.filterTo(linkedSetOf()) { it.isNotBlank() }
        val existing = cachedIds(ids)
        val missing = ids - existing
        val populated = if (missing.isEmpty()) emptySet() else try {
            fetchMissing(missing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        onPage(offset, missing, populated)
        betweenPages()
    }
}
