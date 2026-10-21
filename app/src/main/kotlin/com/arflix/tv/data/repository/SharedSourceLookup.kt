package com.arflix.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transfer a running lookup between prefetch and foreground collectors without
 * restarting it. Unobserved work is cancelled after a short handover grace; only
 * successful results may be briefly reused. Keys must include account/config. */
internal class SharedSourceLookup<T>(
    private val scope: CoroutineScope,
    private val completedTtlMs: Long = 0,
    private val handoverMs: Long = 500,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T>(val result: Deferred<T>) {
        var users = 0
        var completedAt: Long? = null
        var cleanup: Job? = null
    }
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry<T>>()

    suspend fun get(key: String, load: suspend () -> T): T {
        val entry = mutex.withLock {
            entries.entries.removeAll { (_, entry) -> entry.result.isCancelled ||
                entry.completedAt?.let { now() - it >= completedTtlMs } == true }
            val current = entries[key] ?: Entry(scope.async(start = CoroutineStart.LAZY) { load() }).also { created ->
                entries[key] = created
                created.result.invokeOnCompletion { error ->
                    scope.launch {
                        mutex.withLock {
                            if (entries[key] === created) {
                                if (error != null) entries.remove(key) else created.completedAt = now()
                            }
                        }
                    }
                }
                if (entries.size > 256) {
                    entries.entries.firstOrNull { it.key != key && it.value.result.isCompleted }?.let { entries.remove(it.key) }
                }
            }
            current.cleanup?.cancel()
            current.cleanup = null
            current.users++
            current
        }
        try {
            return entry.result.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.users--
                    if (entries[key] === entry && entry.users == 0) {
                        if (entry.result.isCompleted) {
                            if (completedTtlMs <= 0 || entry.result.isCancelled) entries.remove(key)
                        } else entry.cleanup = scope.launch {
                            delay(handoverMs)
                            mutex.withLock {
                                if (entries[key] === entry && entry.users == 0) {
                                    entries.remove(key)
                                    entry.result.cancel()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
