package com.arflix.tv.data.telegram

import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = com.arflix.tv.SentryRegressionTestApplication::class, manifest = Config.NONE)
class TelegramStreamingProxyStartupTest {
    @Test fun concurrentRequestsShareAReadyLoopbackListenerAndRestartWorks() = runBlocking {
        val proxy = TelegramStreamingProxy(RuntimeEnvironment.getApplication(), mockk(relaxed = true))
        try {
            val urls = (1..12).map { async { proxy.getUrl(42) } }.awaitAll()
            assertEquals(1, urls.toSet().size)
            fun checkListener(url: String) {
                val parsed = URL(url)
                assertEquals("127.0.0.1", parsed.host)
                assertTrue(parsed.port > 0)
                val connection = URL(url.replace("/42", "/invalid")).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                try { assertEquals(400, connection.responseCode) } finally { connection.disconnect() }
            }
            checkListener(urls.first())
            proxy.stop()
            checkListener(proxy.getUrl(42))
        } finally { proxy.stop() }
    }
}
