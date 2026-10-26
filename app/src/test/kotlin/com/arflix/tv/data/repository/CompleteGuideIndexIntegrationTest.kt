package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.IptvChannel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CompleteGuideIndexIntegrationTest {
    @Test fun realRepositoryIndexesUnselectedChannelsDuringPlaybackWithoutWholeGuideInMemory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_epg_index.db")
        context.deleteDatabase("arvio_iptv_channel_store.db")
        val now = System.currentTimeMillis()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = formatter.format(Instant.ofEpochMilli(now - 60_000))
        val stop = formatter.format(Instant.ofEpochMilli(now + 3_600_000))
        val xml = buildString {
            append("<tv>")
            repeat(1000) { append("<channel id=\"c$it\"><display-name>Channel $it</display-name></channel>") }
            repeat(1000) { append("<programme channel=\"c$it\" start=\"$start\" stop=\"$stop\"><title>Programme $it</title></programme>") }
            append("</tv>")
        }
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath in setOf("/epg/full.sqlite.gz", "/epg/manifest")) {
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Unavailable").body("".toResponseBody()).build()
            }
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(xml.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val gate = IptvEpgPlaybackGate().apply { setPlaybackActive(true) }
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true), gate,primaryAccess=primaryAccessFixture()))
        every { repository.observeConfig() } returns flowOf(IptvConfig(
            m3uUrl = "https://provider.example.test/list.m3u", epgUrl = "https://guide.example.test/feed.xml"))
        fun field(name: String) = IptvRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("currentEpgIndexKey").set(repository, "fixture-guide")
        field("cachedChannels").set(repository, (0 until 1000).map {
            IptvChannel("list_1:channel-$it", "Channel $it", "https://provider.example.test/live/$it", "Group", epgId = "c$it")
        })
        try {
            repository.populateCompleteGuideIndex()
            val ids = (0 until 1000).mapTo(linkedSetOf()) { "list_1:channel-$it" }
            val guide = repository.indexedGuideViewport(ids, now - 1, now + 7_200_000)
            assertEquals(1000, guide.programsByChannel.size)
            assertEquals("Programme 999", guide.programsByChannel.getValue("list_1:channel-999").single().title)
            assertEquals(1, downloads)
            assertEquals(12, repository.readIndexedGuideForPrefetch(ids.take(12).toSet()).size)
            assertTrue((field("cachedNowNext").get(repository) as Map<*, *>).isEmpty())
        } finally {
            @Suppress("UNCHECKED_CAST")
            (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value.close()
            (field("backgroundEpgDispatcher").get(repository) as ExecutorCoroutineDispatcher).close()
        }
    }
}
