package com.arflix.tv.data.repository

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Persists bounded parser snapshots off the XML parser thread. The queue is
 * deliberately tiny: when storage is slow the parser applies backpressure
 * rather than retaining a large guide in memory.
 */
internal class IncrementalGuideBatchWriter<T>(
    queueCapacity: Int = 2,
    private val write: (T) -> Unit,
) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun submit(batch: T) {
        executor.execute { write(batch) }
    }

    fun finish() {
        executor.shutdown()
        executor.awaitTermination(60L, TimeUnit.SECONDS)
    }
}
