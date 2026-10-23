package com.arflix.tv.management

import android.content.Context
import com.arflix.tv.ArflixApplication
import com.arflix.tv.network.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache-owner APIs only. Deliberately NO file/directory traversal or source purge. */
internal suspend fun clearManagementExpendableCache(context: Context): ManagementCacheReport = withContext(Dispatchers.IO) {
    clearManagementCacheOwners(
        images = { (context.applicationContext as? ArflixApplication)?.clearManagedImageCaches() ?: error("Unavailable") },
        http = { OkHttpProvider.clearExpendableHttpResponses() }
    )
}

internal data class ManagementCacheReport(val images: Boolean, val http: Boolean) {
    val succeeded: Boolean get() = images && http
    val summary: String get() = "Image cache: ${if (images) "cleared" else "unavailable"}; ordinary HTTP cache: ${if (http) "cleared" else "unavailable"}. " +
        "Login, profiles, guides, favourites, enrollment, update files and playback buffers untouched. Caches may refill immediately."
}
internal fun clearManagementCacheOwners(images: () -> Unit, http: () -> Unit): ManagementCacheReport {
    val imageOk = runCatching(images).isSuccess
    val httpOk = runCatching(http).isSuccess
    return ManagementCacheReport(imageOk, httpOk)
}
