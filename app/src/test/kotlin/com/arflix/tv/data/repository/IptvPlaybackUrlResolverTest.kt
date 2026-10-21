package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IptvPlaybackUrlResolverTest {
    @Test fun `native OwnTV tunes skip redundant redirect probes but recovery still verifies HLS`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/x-mpegURL").body("".toResponseBody()).build()
        }.build()
        try {
            val resolver = IptvPlaybackUrlResolver(client)
            val url = "http://provider.test/live/user/pass/channel-slug"
            assertThat(resolver.resolve(url, emptyMap(), nativeContainerDetection = true).url).isEqualTo(url)
            assertThat(calls.get()).isEqualTo(0)
            assertThat(resolver.resolve(url, emptyMap(), forceRefresh = true, nativeContainerDetection = true).isHls).isTrue()
            assertThat(calls.get()).isEqualTo(1)
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun `changing channels cancels the outstanding provider preflight`() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val callRef = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) { callRef.set(call) }
        }).addInterceptor { chain ->
            entered.countDown()
            release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/x-mpegURL").body("".toResponseBody()).build()
        }.build()
        try {
            val resolver = IptvPlaybackUrlResolver(client)
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                resolver.resolve("http://provider.test/live/user/pass/channel-slug", emptyMap())
            }
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            job.cancelAndJoin()
            assertThat(callRef.get().isCanceled()).isTrue()
        } finally { release.countDown(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun `slow preflight is cancelled within one budget and does not poison the cache`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.ConcurrentLinkedQueue<okhttp3.Call>()
        val slow = java.util.concurrent.atomic.AtomicBoolean(true)
        val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) { calls.add(call) }
        }).addInterceptor { chain ->
            if (slow.get()) release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/x-mpegURL").body("".toResponseBody()).build()
        }.build()
        try {
            val resolver = IptvPlaybackUrlResolver(client, probeTimeoutMs = 150L)
            val url = "http://provider.test/live/user/pass/channel-slug"
            val started = System.nanoTime()
            val fallback = resolver.resolve(url, emptyMap())
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_500L)
            assertThat(fallback.url).isEqualTo(url)
            assertThat(fallback.isHls).isFalse()
            assertThat(calls.first().isCanceled()).isTrue()
            slow.set(false); release.countDown()
            val fresh = resolver.resolve(url, emptyMap())
            assertThat(fresh.isHls).isTrue()
            assertThat(calls.size).isEqualTo(2)
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `explicit recovery retains a longer budget for slow providers`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Thread.sleep(100)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/x-mpegURL").body("".toResponseBody()).build()
        }.build()
        try {
            val resolver = IptvPlaybackUrlResolver(client, probeTimeoutMs = 20L)
            val url = "http://provider.test/live/user/pass/channel-slug"
            assertThat(resolver.resolve(url, emptyMap()).isHls).isFalse()
            assertThat(resolver.resolve(url, emptyMap(), forceRefresh = true).isHls).isTrue()
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test
    fun `extensionless slug live URL resolves redirect and HLS type`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                val finalRequest = chain.request().newBuilder()
                    .url("http://provider.test/hls/animal-planet/index.m3u8")
                    .build()
                Response.Builder()
                    .request(finalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/x-mpegURL")
                    .body("".toResponseBody("application/x-mpegURL".toMediaType()))
                    .build()
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val first = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/animal-planet",
            headers = emptyMap(),
        )
        val cached = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/animal-planet",
            headers = emptyMap(),
        )

        assertThat(first.url).isEqualTo("http://provider.test/hls/animal-planet/index.m3u8")
        assertThat(first.isHls).isTrue()
        assertThat(cached).isEqualTo(first)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `HLS content type is retained when redirect target has no extension`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .body("".toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val target = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel-slug",
            headers = emptyMap(),
        )

        assertThat(target.url).isEqualTo("http://provider.test/live/user/pass/channel-slug")
        assertThat(target.isHls).isTrue()
    }

    @Test
    fun `GET probe detects HLS body when provider rejects HEAD`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = calls.incrementAndGet()
                if (chain.request().method == "HEAD") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(405)
                        .message("Method Not Allowed")
                        .header("Content-Type", "text/html")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/plain")
                        .body("#EXTM3U\n#EXT-X-VERSION:3".toResponseBody("text/plain".toMediaType()))
                        .build()
                }.also {
                    assertThat(call).isAtMost(2)
                }
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val target = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel-slug",
            headers = emptyMap(),
        )

        assertThat(target.isHls).isTrue()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `known direct and adaptive URLs skip redirect probe`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { error("Redirect probe should not run") }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val numericXtream = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/12345",
            headers = emptyMap(),
        )
        val transportStream = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel.ts",
            headers = emptyMap(),
        )
        val hls = resolver.resolve(
            rawUrl = "http://provider.test/hls/channel/index.m3u8",
            headers = emptyMap(),
        )
        val hlsParam = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/stream?output=m3u8",
            headers = emptyMap(),
        )
        val formatHls = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/stream?format=hls",
            headers = emptyMap(),
        )

        assertThat(numericXtream.isHls).isFalse()
        assertThat(transportStream.isHls).isFalse()
        assertThat(hls.isHls).isTrue()
        assertThat(hlsParam.isHls).isTrue()
        assertThat(formatHls.isHls).isTrue()
    }
}
