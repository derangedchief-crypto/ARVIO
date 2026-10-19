package com.arflix.tv.data.repository

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.arflix.tv.data.model.IptvChannel
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ServerCachedGuideIntegrationTest {
    @Test fun completeServerCacheSkipsProviderAndDoesNotFillMemoryCache() = verifyCache(1000)
    @Test fun partialServerCacheFetchesOnlyMissingProviderChannels() = verifyCache(1)
    @Test fun expiredServerCacheFallsBackToProvider() = verifyCache(1000, expired = true)
    @Test fun corruptServerCacheFallsBackToProvider() = verifyCache(0, corrupt = true)
    @Test fun unavailableServerCacheFallsBackToProvider() = verifyCache(0, unavailable = true)
    @Test fun serverDisplayNamesMatchWhenXmlIdsDiffer() = verifyCache(1000, matchByName = true)
    @Test fun completedHomeLoadDoesNotRestartWhenTvOrWorkerRequestsIt() = verifyCache(1000, coordinated = true)
    @Test fun providerWithoutListingsDoesNotCauseImmediateWholeGuideRestart() = verifyCache(1, coordinated = true, omitLast = true)

    private fun verifyCache(
        serverCount: Int,
        expired: Boolean = false,
        corrupt: Boolean = false,
        unavailable: Boolean = false,
        matchByName: Boolean = false,
        coordinated: Boolean = false,
        omitLast: Boolean = false,
    ) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_epg_index.db")
        context.deleteDatabase("arvio_iptv_channel_store.db")
        context.getSharedPreferences("arvio_guide_refresh", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val now = System.currentTimeMillis()
        val fixture = File.createTempFile("server-guide-fixture", ".sqlite", context.cacheDir)
        val gzipped = try {
            SQLiteDatabase.openOrCreateDatabase(fixture, null).use { db ->
                db.execSQL("CREATE TABLE channels(channel_id TEXT PRIMARY KEY, display_name TEXT)")
                db.execSQL("CREATE TABLE programmes(channel_id TEXT,start_ms INTEGER,end_ms INTEGER,title TEXT,description TEXT)")
                db.execSQL("CREATE INDEX ix_programmes ON programmes(channel_id,start_ms,end_ms)")
                db.beginTransaction()
                try {
                    repeat(serverCount) { i ->
                        val id = if (matchByName) "Server.ID.$i" else "c$i"
                        db.execSQL("INSERT INTO channels VALUES(?,?)", arrayOf(id, "Channel $i"))
                        db.execSQL("INSERT INTO programmes VALUES(?,?,?,?,?)", arrayOf<Any>(id,
                            now - if (expired) 86_400_000L else 60_000L,
                            now + if (expired) -82_800_000L else 3_600_000L,
                            "Server programme $i", "From shared cache"))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            ByteArrayOutputStream().also { output ->
                GZIPOutputStream(output).use { it.write(fixture.readBytes()) }
            }.toByteArray()
        } finally { SQLiteDatabase.deleteDatabase(fixture) }
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = formatter.format(Instant.ofEpochMilli(now - 60_000))
        val stop = formatter.format(Instant.ofEpochMilli(now + 3_600_000))
        val xml = buildString {
            append("<tv>")
            repeat(if (omitLast) 999 else 1000) { append("<programme channel=\"c$it\" start=\"$start\" stop=\"$stop\"><title>Provider programme $it</title></programme>") }
            append("</tv>")
        }
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val server = chain.request().url.encodedPath == "/epg/full.sqlite.gz"
            requested += if (server) "server" else "provider"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (server && unavailable) 503 else 200).message("Fixture")
                .body(if (server) (if (corrupt) byteArrayOf(1, 2, 3) else gzipped).toResponseBody()
                    else xml.toResponseBody()).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true),
            IptvEpgPlaybackGate().apply { setPlaybackActive(true) }))
        every { repository.observeConfig() } returns flowOf(IptvConfig(
            m3uUrl = "https://provider.example.test/list.m3u", epgUrl = "https://guide.example.test/feed.xml"))
        fun field(name: String) = IptvRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("currentEpgIndexKey").set(repository, "fixture|guide")
        coEvery { repository.warmupFromCacheOnly() } returns Unit
        field("cachedChannels").set(repository, (0 until 1000).map {
            IptvChannel("list_1:channel-$it", "Channel $it", "https://provider.example.test/live/$it", "Group", epgId = "c$it")
        })
        try {
            if (coordinated) {
                repository.requestGuideRefresh().join()
                repository.requestGuideRefresh().join()
                repository.requestGuideRefresh().join()
                assertFalse(repository.guideRefreshState.value.loading)
                assertFalse(repository.guideRefreshState.value.failed)
            } else repository.populateCompleteGuideIndex()
            val ids = (0 until 1000).mapTo(linkedSetOf()) { "list_1:channel-$it" }
            val guide = repository.indexedGuideViewport(ids, now, now + 7_200_000)
            assertEquals(if (omitLast) 999 else 1000, guide.programsByChannel.size)
            val usedServer = !expired && !corrupt && !unavailable && serverCount > 0
            repeat(if (omitLast) 999 else 1000) { i ->
                val expected = if (usedServer && i < serverCount) "Server programme $i" else "Provider programme $i"
                assertEquals(expected, guide.programsByChannel.getValue("list_1:channel-$i").single().title)
            }
            assertEquals(if (usedServer && serverCount == 1000) listOf("server") else listOf("server", "provider"), requested)
            assertTrue((field("cachedNowNext").get(repository) as Map<*, *>).isEmpty())
            val leftovers = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("daily_epg_") }
            assertTrue("Temporary guide files remain: $leftovers", leftovers.isEmpty())
        } finally {
            @Suppress("UNCHECKED_CAST")
            (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value.close()
            (field("backgroundEpgDispatcher").get(repository) as ExecutorCoroutineDispatcher).close()
        }
    }
}
