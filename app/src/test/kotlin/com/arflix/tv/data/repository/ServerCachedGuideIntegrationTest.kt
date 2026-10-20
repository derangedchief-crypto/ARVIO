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
    @Test fun unchangedServerSnapshotDoesNotDownloadOrRewriteLoadedListings() = verifyCache(24, total = 24, recheck = true)
    @Test fun serverOutageAfterSuccessfulLoadKeepsGuideAndReportsRetainedCache() = verifyCache(24, total = 24, offlineAfterLoad = true)
    @Test fun usableServerCacheUsesOnlyMissingXtreamChannelApiNotFullXmltv() = verifyCache(23, total = 24, xtream = true, coordinated = true)
    @Test fun unchangedSnapshotExtendsFutureWindowWithoutRewritingCurrentGuide() = verifyCache(24, total = 24, futureExtension = true)
    @Test fun oldCompletedCheckpointCannotHidePartialGuideAfterUpgrade() = verifyCache(24, total = 24, coordinated = true, oldCheckpoint = true)
    @Test fun visibleGuideRecheckUsesLocalIndexWithoutAnotherXmltvDownload() = verifyCache(24, total = 24, coordinated = true, visibleRecheck = true)
    @Test fun unlistedVisibleChannelDoesNotRestartWholeCompletedXmltvImport() = verifyCache(1, total = 24, coordinated = true, omitLast = true, missingVisibleRecheck = true)
    @Test fun interruptedGuideRechecksDespiteRecentCompletionMarker() = verifyCache(24, total = 24, coordinated = true, recheckStale = true)

    private fun verifyCache(
        serverCount: Int,
        expired: Boolean = false,
        corrupt: Boolean = false,
        unavailable: Boolean = false,
        matchByName: Boolean = false,
        coordinated: Boolean = false,
        omitLast: Boolean = false,
        total: Int = 1000,
        recheck: Boolean = false,
        offlineAfterLoad: Boolean = false,
        xtream: Boolean = false,
        futureExtension: Boolean = false,
        oldCheckpoint: Boolean = false,
        visibleRecheck: Boolean = false,
        missingVisibleRecheck: Boolean = false,
        recheckStale: Boolean = false,
    ) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_epg_index.db")
        context.deleteDatabase("arvio_iptv_channel_store.db")
        context.getSharedPreferences("arvio_guide_refresh", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("server_guide_snapshot", 0).edit().clear().commit()
        File(context.filesDir, "server_guide_snapshots").listFiles().orEmpty().forEach { SQLiteDatabase.deleteDatabase(it) }
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
                        if (futureExtension) db.execSQL("INSERT INTO programmes VALUES(?,?,?,?,?)", arrayOf<Any>(id,
                            now + 4 * 3_600_000, now + 5 * 3_600_000, "Future programme $i", "Future cached listing"))
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
            repeat(if (omitLast) total - 1 else total) { append("<programme channel=\"c$it\" start=\"$start\" stop=\"$stop\"><title>Provider programme $it</title></programme>") }
            append("</tv>")
        }
        val requested = mutableListOf<String>()
        var offline = unavailable
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/epg/manifest") {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(gzipped).joinToString("") { "%02x".format(it) }
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (offline) 503 else 200).message("Manifest")
                    .body("{\"sha256\":\"$hash\",\"bytes\":${gzipped.size}}".toResponseBody()).build()
            }
            if (chain.request().url.queryParameter("action") == "get_short_epg") {
                requested += "short"
                val stream = chain.request().url.queryParameter("stream_id")!!.toInt()
                val i = stream - 100
                val title = java.util.Base64.getEncoder().encodeToString("Provider programme $i".toByteArray())
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Short guide")
                    .body("""{"epg_listings":[{"title":"$title","epg_id":"c$i","stream_id":"$stream","start_timestamp":"${(now - 60_000) / 1000}","stop_timestamp":"${(now + 3_600_000) / 1000}"}]}""".toResponseBody()).build()
            }
            val server = chain.request().url.encodedPath == "/epg/full.sqlite.gz"
            requested += if (server) "server" else "provider"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (server && offline) 503 else 200).message("Fixture")
                .body(if (server) (if (corrupt) byteArrayOf(1, 2, 3) else gzipped).toResponseBody()
                    else xml.toResponseBody()).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true),
            IptvEpgPlaybackGate().apply { setPlaybackActive(true) }))
        val config = IptvConfig(
            m3uUrl = if (xtream) "https://provider.example.test/get.php?username=user&password=pass&type=m3u_plus" else "https://provider.example.test/list.m3u",
            epgUrl = "https://guide.example.test/feed.xml")
        every { repository.observeConfig() } returns flowOf(config)
        fun field(name: String) = IptvRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("currentEpgIndexKey").set(repository, "fixture|guide")
        coEvery { repository.warmupFromCacheOnly() } returns Unit
        field("cachedChannels").set(repository, (0 until total).map {
            IptvChannel("list_1:channel-$it", "Channel $it", if (xtream) "https://provider.example.test/live/user/pass/${it + 100}.ts" else "https://provider.example.test/live/$it",
                "Group", epgId = "c$it", xtreamStreamId = if (xtream) it + 100 else null)
        })
        if (oldCheckpoint) {
            val signature = IptvRepository::class.java.getDeclaredMethod("buildEpgSourceSignature", IptvConfig::class.java)
                .apply { isAccessible = true }.invoke(repository, config) as String
            context.getSharedPreferences("arvio_guide_refresh", 0).edit()
                .putLong("completed_fixture|guide|$signature", now).commit()
            @Suppress("UNCHECKED_CAST") val index = (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value
            index.mergeChannels("fixture|guide", mapOf("list_1:channel-0" to com.arflix.tv.data.model.IptvNowNext(now =
                com.arflix.tv.data.model.IptvProgram("Already saved", startUtcMillis = now - 60_000, endUtcMillis = now + 3_600_000))), now)
        }
        try {
            if (coordinated) {
                repository.requestGuideRefresh().join()
                repository.requestGuideRefresh().join()
                repository.requestGuideRefresh().join()
                assertFalse(repository.guideRefreshState.value.loading)
                assertFalse(repository.guideRefreshState.value.failed)
            } else repository.populateCompleteGuideIndex()
            if (visibleRecheck) {
                val cached = repository.refreshEpgForChannels(setOf("list_1:channel-0"), retainInMemory = false)
                assertEquals("Server programme 0", cached!!.getValue("list_1:channel-0").now!!.title)
            }
            if (missingVisibleRecheck) assertNull(repository.refreshEpgForChannels(setOf("list_1:channel-${total - 1}"), retainInMemory = false))
            if (recheckStale) {
                @Suppress("UNCHECKED_CAST") val index = (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value
                index.markRefreshFailed("fixture|guide")
                repository.requestGuideRefresh().join()
                assertEquals("ready", index.sourceState("fixture|guide")!!.status)
            }
            if (recheck || futureExtension) {
                @Suppress("UNCHECKED_CAST") val index = (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value
                index.mergeChannels("fixture|guide", mapOf("list_1:channel-0" to com.arflix.tv.data.model.IptvNowNext(now =
                    com.arflix.tv.data.model.IptvProgram("Local correction", startUtcMillis = now - 60_000, endUtcMillis = now + 3_600_000))), now)
                if (futureExtension) {
                    index.writableDatabase.delete("epg_programs", "source_key=? AND channel_id=? AND start_ms=?",
                        arrayOf("fixture|guide", "list_1:channel-0", (now + 4 * 3_600_000).toString()))
                    val preferences = context.getSharedPreferences("arvio_guide_refresh", 0)
                    val edit = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("server_import_") && it.endsWith("_end") }.forEach {
                        edit.putLong(it, now + 3_600_000)
                    }
                    edit.commit()
                }
                repository.populateCompleteGuideIndex()
                if (futureExtension) assertEquals("Future programme 0", repository.indexedGuideViewport(setOf("list_1:channel-0"),
                    now + 3 * 3_600_000, now + 6 * 3_600_000).programsByChannel.getValue("list_1:channel-0").single().title)
            }
            if (offlineAfterLoad) {
                @Suppress("UNCHECKED_CAST") val index = (field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>).value
                index.mergeChannels("fixture|guide", mapOf("list_1:channel-0" to com.arflix.tv.data.model.IptvNowNext(now =
                    com.arflix.tv.data.model.IptvProgram("Local correction", startUtcMillis = now - 60_000, endUtcMillis = now + 3_600_000))), now)
                val preferences = context.getSharedPreferences("arvio_guide_refresh", 0)
                val edit = preferences.edit()
                preferences.all.keys.filter { it.startsWith("server_import_") }.forEach { edit.remove(it) }
                edit.commit()
                offline = true
                repository.requestGuideRefresh(force = true).join()
                assertTrue(repository.guideRefreshState.value.failed)
                assertTrue(repository.guideRefreshState.value.message.orEmpty().contains("saved listings retained"))
            }
            val ids = (0 until total).mapTo(linkedSetOf()) { "list_1:channel-$it" }
            val guide = repository.indexedGuideViewport(ids, now, now + 7_200_000)
            assertEquals(if (omitLast) total - 1 else total, guide.programsByChannel.size)
            val usedServer = !expired && !corrupt && !unavailable && serverCount > 0
            repeat(if (omitLast) total - 1 else total) { i ->
                val expected = if ((recheck || futureExtension || offlineAfterLoad) && i == 0) "Local correction" else if (usedServer && i < serverCount) "Server programme $i" else "Provider programme $i"
                assertEquals(expected, guide.programsByChannel.getValue("list_1:channel-$i").single().title)
            }
            assertEquals(if (xtream) listOf("server", "short") else if (usedServer && serverCount == total) listOf("server") else listOf("server", "provider"), requested)
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
