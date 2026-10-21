package com.arflix.tv.data.repository

import java.util.concurrent.ConcurrentHashMap

/** Reuse a complete, verified catalogue generation, not an arbitrary disk-cache
 * timestamp. Stale/unverified misses still require a successful provider check. */
internal class VerifiedVodCatalogs(private val freshMs: Long = 5 * 60_000L) {
    private val generations = ConcurrentHashMap<String, Long>()
    fun mark(key: String, generation: Long) {
        if (generations.size >= 256) generations.clear()
        generations[key] = generation
    }
    fun isFresh(key: String, generation: Long, now: Long = System.currentTimeMillis()): Boolean =
        generation > 0 && generations[key] == generation && now - generation in 0 until freshMs
}
