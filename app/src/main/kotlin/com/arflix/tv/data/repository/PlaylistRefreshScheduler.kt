package com.arflix.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Process-owned polling survives activity stops without keeping Android awake. */
internal class PlaylistRefreshScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var polling: Job? = null

    @Synchronized
    fun start(refresh: () -> Job) {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                // Share an in-flight refresh; never stack slow provider requests.
                refresh()
            }
        }
    }
}
