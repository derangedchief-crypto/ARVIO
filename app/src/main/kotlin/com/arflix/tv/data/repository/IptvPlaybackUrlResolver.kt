package com.arflix.tv.data.repository

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlin.coroutines.resume

internal data class IptvPlaybackTarget(
    val url: String,
    val isHls: Boolean = false,
)

internal class IptvPlaybackUrlResolver(
    private val client: OkHttpClient,
    private val cacheTtlMs: Long = 5 * 60_000L,
    private val maxCacheEntries: Int = 256,
    private val probeTimeoutMs: Long = 1_500L,
) {
    private data class ProbeResult(
        val target: IptvPlaybackTarget,
        val isConclusive: Boolean,
    )

    private data class CachedTarget(
        val target: IptvPlaybackTarget,
        val resolvedAtMs: Long,
    )

    private val cache = LinkedHashMap<String, CachedTarget>()

    suspend fun resolve(
        rawUrl: String,
        headers: Map<String, String>,
        forceRefresh: Boolean = false,
        nativeContainerDetection: Boolean = false,
    ): IptvPlaybackTarget {
        val url = rawUrl.trim()
        val inferredTarget = IptvPlaybackTarget(
            url = url,
            isHls = looksLikeHlsPlaybackUrl(url),
        )
        // OwnTV detects the actual container and follows redirects itself. Do
        // not open a second provider connection merely to discover its format.
        // DRM/Media3 and explicit recovery retain the typed preflight below.
        if ((nativeContainerDetection && !forceRefresh) || !shouldResolveIptvPlaybackRedirect(url)) return inferredTarget

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(cache) {
                cache[url]
                    ?.takeIf { now - it.resolvedAtMs <= cacheTtlMs }
                    ?.let { return it.target }
            }
        }

        // Optional preflight must not spend two independent five-second calls
        // ahead of the real player. Cancellation also closes its provider socket.
        // Explicit recovery gets the old, more patient budget for slow sources.
        val resolved = withTimeoutOrNull(if (forceRefresh) maxOf(probeTimeoutMs, 5_000L) else probeTimeoutMs) {
            val headProbe = executeProbe(url, headers, useHead = true)
            if (headProbe?.isConclusive == true) {
                headProbe
            } else {
                executeProbe(url, headers, useHead = false)
            }
        }

        if (resolved?.isConclusive == true) synchronized(cache) {
            cache[url] = CachedTarget(resolved.target, System.currentTimeMillis())
            while (cache.size > maxCacheEntries) {
                val firstKey = cache.keys.firstOrNull() ?: break
                cache.remove(firstKey)
            }
        }
        return resolved?.target ?: inferredTarget
    }

    private suspend fun executeProbe(
        url: String,
        headers: Map<String, String>,
        useHead: Boolean,
    ): ProbeResult? {
        return try {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (useHead) {
                        head()
                    } else {
                        get()
                        header("Range", "bytes=0-63")
                    }
                }
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .apply {
                    headers.forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank() && !name.equals("Range", ignoreCase = true)) {
                            header(name, value)
                        }
                    }
                }
                .build()

            suspendCancellableCoroutine<ProbeResult?> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = try { response.use {
                            if (!it.isSuccessful) null else probeResponse(it, url, useHead)
                        } } catch (_: Exception) { null }
                        if (continuation.isActive) continuation.resume(result)
                    }
                })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun probeResponse(response: Response, url: String, useHead: Boolean): ProbeResult {
        val finalUrl = response.request.url.toString().ifBlank { url }
        val contentType = response.header("Content-Type")
        val bodyStartsWithM3u = !useHead && response.peekBody(64).string().trimStart()
            .startsWith("#EXTM3U", ignoreCase = true)
        val target = IptvPlaybackTarget(url = finalUrl,
            isHls = looksLikeHlsPlaybackUrl(finalUrl) || contentType.isHlsContentType() || bodyStartsWithM3u)
        return ProbeResult(target, isConclusive = finalUrl != url || target.isHls || contentType.isDirectMediaContentType())
    }
}

internal fun shouldResolveIptvPlaybackRedirect(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return false
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return false
    }
    if (looksLikeHlsPlaybackUrl(trimmed)) return false

    val uri = try { URI(trimmed) } catch (e: java.net.URISyntaxException) { null } catch (e: java.lang.IllegalArgumentException) { null } ?: return false
    val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.US)
    val lastSegment = path.substringAfterLast('/')
    if (lastSegment.isBlank() || lastSegment.contains('.')) return false

    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    if (segments.size < 4 || !segments.first().equals("live", ignoreCase = true)) return false

    // Standard Xtream numeric IDs are direct MPEG-TS streams. Slug-based providers
    // commonly redirect to HLS, which Media3 cannot infer from the original URL.
    return lastSegment.toLongOrNull() == null
}

internal fun looksLikeHlsPlaybackUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    val path = lower.substringBefore('?').substringBefore('#')
    return path.endsWith(".m3u8") ||
        path.contains("/hls/") ||
        "output=m3u8" in lower ||
        "format=hls" in lower
}

private fun String?.isHlsContentType(): Boolean {
    val value = this.orEmpty().lowercase(Locale.US)
    return "mpegurl" in value || "vnd.apple.mpegurl" in value
}

private fun String?.isDirectMediaContentType(): Boolean {
    val value = this.orEmpty().lowercase(Locale.US).substringBefore(';').trim()
    return value.startsWith("video/") ||
        value.startsWith("audio/") ||
        value == "application/octet-stream"
}
