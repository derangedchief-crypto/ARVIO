package com.arflix.tv.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arflix.tv.data.repository.IptvRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Durable guide refresh for the local SQLite EPG index.
 *
 * Live TV still renders cached channels immediately, but this worker lets the
 * expensive guide fetch/parse continue independently of the TV screen's
 * ViewModel. It is deliberately unique work so an app-open request and the
 * periodic schedule can never parse the same guide concurrently.
 */
@HiltWorker
class IptvEpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val iptvRepository: IptvRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // Join the same application-owned job used by Home and TV. A tab change
        // cannot cancel it, and overlapping worker/UI requests cannot duplicate it.
        iptvRepository.requestGuideRefresh().join()
        if (iptvRepository.guideRefreshState.value.failed) Result.retry() else Result.success()
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) {
            throw error
        } else {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val APP_OPEN_WORK_NAME = "iptv_epg_refresh_on_open"
        const val PERIODIC_WORK_NAME = "iptv_epg_refresh_periodic"
        const val TAG = "iptv_epg_refresh"
        const val APP_OPEN_DELAY_SECONDS = 5L
        const val PERIODIC_INTERVAL_HOURS = 6L
        private const val MAX_RETRIES = 3
    }
}
