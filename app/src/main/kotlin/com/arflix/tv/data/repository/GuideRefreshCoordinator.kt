package com.arflix.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A refresh belongs to the application, never to a tab or its waiting coroutine. */
internal class GuideRefreshCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var running: Job? = null

    @Synchronized
    fun request(work: suspend () -> Unit): Job {
        running?.takeIf { it.isActive }?.let { return it }
        return scope.launch(start = CoroutineStart.LAZY) { work() }.also {
            running = it
            it.start()
        }
    }
}

data class GuideRefreshState(
    val loading: Boolean = false,
    val message: String? = null,
    val percent: Int? = null,
    val visited: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false,
)
