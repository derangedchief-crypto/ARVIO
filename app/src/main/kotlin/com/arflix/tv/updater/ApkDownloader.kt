package com.arflix.tv.updater

import android.content.Context
import com.arflix.tv.R
import com.arflix.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    suspend fun download(
        url: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val coroutineContext = currentCoroutineContext()
            coroutineContext.ensureActive()
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            // Use a dedicated client with longer timeouts for large APK downloads
            val downloadClient = okHttpClient.newBuilder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(BuildConfig.BRAND_ID.isBlank())
                .followSslRedirects(BuildConfig.BRAND_ID.isBlank())
                .build()

            val request = Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(context.getString(R.string.update_error_download_http, response.code))
                }

                val body = response.body ?: error(context.getString(R.string.update_error_empty_download_body))
                val total = body.contentLength().takeIf { it > 0L }
                val progress = DownloadProgressGate()
                onProgress(0L,total)
                progress.shouldEmit(android.os.SystemClock.elapsedRealtime())
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (progress.shouldEmit(android.os.SystemClock.elapsedRealtime())) onProgress(downloaded, total)
                        }
                        output.flush()
                        check(total == null || downloaded == total) { "The update download was incomplete. Please retry." }
                        onProgress(downloaded,total)
                    }
                }
            }

            destinationFile
        }.onFailure { if (it is CancellationException) throw it }
    }
}

/** A 130MB APK previously emitted ~16,000 UI updates (one per 8KB). Keep disk/
 * network work fast while limiting Compose progress recomposition to 4Hz. */
internal class DownloadProgressGate(private val intervalMs: Long = 250L) {
    private var last: Long? = null
    fun shouldEmit(now: Long): Boolean {
        val previous = last
        if (previous != null && now >= previous && now - previous < intervalMs) return false
        last = now
        return true
    }
}
