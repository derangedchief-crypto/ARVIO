package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.util.CatalogueDiskFile
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Job
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
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LaunchContentRefreshIntegrationTest {
    @Test fun launchRefreshUpdatesAllThreeCataloguesOnlyOnce() = fixture()
    @Test fun sourcesReuseCatalogueVerifiedByHomeRefresh() = fixture(checkSourceReuse = true)
    @Test fun failedVodResponsesKeepPreviousCatalogues() = fixture(failVod = true)
    @Test fun validEmptyVodListsRemoveAllPreviousItems() = fixture(emptyVod = true)
    @Test fun malformedVodListsCannotErasePreviousItems() = fixture(malformedVod = true)
    @Test fun profileSwitchDiscardsTheInFlightResponse() = fixture(switchProfile = true)
    @Test fun reopeningAfterDebounceRechecksButQuickReturnsDoNot() = fixture(recheck = true)

    private fun fixture(failVod: Boolean = false, emptyVod: Boolean = false,
        malformedVod: Boolean = false, switchProfile: Boolean = false, recheck: Boolean = false,
        checkSourceReuse: Boolean = false) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_channel_store.db")
        val host = "https://provider.example.test"
        val owner = "$host|fixture-user|fixture-pass"
        val hash = MessageDigest.getInstance("MD5").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "xtream_vod_disk_cache").apply { mkdirs() }
        val vodFile = File(dir, "vod_$hash.json")
        val seriesFile = File(dir, "series_$hash.json")
        val oldVod = "{\"savedAtMs\":1,\"items\":[{\"stream_id\":22,\"name\":\"Old movie\"}]}"
        val oldSeries = "{\"savedAtMs\":1,\"items\":[{\"series_id\":32,\"name\":\"Old series\"}]}"
        vodFile.writeText(oldVod)
        seriesFile.writeText(oldSeries)
        var profileId = "fixture"
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action").orEmpty()
            actions.add(action)
            if (switchProfile && action == "get_live_streams") profileId = "other"
            val body = when (action) {
                "get_live_categories" -> "[{\"category_id\":\"1\",\"category_name\":\"News\"}]"
                "get_live_streams" -> "[{\"stream_id\":11,\"name\":\"Kept channel\",\"category_id\":\"1\"},{\"stream_id\":13,\"name\":\"New channel\",\"category_id\":\"1\"}]"
                "get_vod_streams" -> if (emptyVod) "[]" else if (malformedVod) "[{\"error\":\"Unavailable\"}]" else "[{\"stream_id\":23,\"name\":\"New movie\"}]"
                "get_series" -> if (emptyVod) "[]" else if (malformedVod) "{\"error\":\"Unavailable\"}" else "[{\"series_id\":33,\"name\":\"New series\"}]"
                else -> error("Unexpected launch request: $action")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (failVod && action in setOf("get_vod_streams", "get_series")) 503 else 200)
                .message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } answers { profileId }
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true), IptvEpgPlaybackGate()))
        coEvery { repository.warmupFromCacheOnly() } returns Unit
        every { repository.observeConfig() } returns flowOf(IptvConfig(m3uUrl = "$host/get.php?username=fixture-user&password=fixture-pass&type=m3u_plus"))
        every { repository.requestGuideRefresh(any()) } returns Job().apply { complete() }
        fun field(name: String) = IptvRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("currentEpgIndexKey").set(repository, "fixture|source")
        field("xtreamVodCacheKey").set(repository, owner)
        val previous = listOf(
            IptvChannel("list_1:xtream:11", "Kept channel", "$host/live/11", "News"),
            IptvChannel("list_1:xtream:12", "Removed channel", "$host/live/12", "News"),
            IptvChannel("offline:99", "Other provider", "$host/live/99", "Other"))
        field("cachedChannels").set(repository, previous)
        try {
            repository.requestLaunchContentRefresh().join()
            if (checkSourceReuse) {
                assertTrue(repository.findMovieVodSources("Not In This Catalogue", 2026,
                    verifyBeforeFallback = true).isEmpty())
            }
            if (!switchProfile) {
                repository.requestLaunchContentRefresh().join()
                repository.prefetchFreshStartupData()
                repository.requestLaunchContentRefresh(recheckOnLaunch = true).join()
                if (recheck) {
                    @Suppress("UNCHECKED_CAST") val checked = field("launchContentCheckedAt").get(repository) as MutableMap<String, Long>
                    checked.keys.toList().forEach { checked[it] = System.currentTimeMillis() - 16 * 60_000L }
                    repository.requestLaunchContentRefresh(recheckOnLaunch = true).join()
                }
            }
            @Suppress("UNCHECKED_CAST") val rows = field("cachedChannels").get(repository) as List<IptvChannel>
            if (switchProfile) {
                assertEquals(previous, rows)
                assertEquals(oldVod, vodFile.readText())
                assertEquals(oldSeries, seriesFile.readText())
            } else {
                assertEquals(setOf("list_1:xtream:11", "list_1:xtream:13", "offline:99"), rows.map { it.id }.toSet())
                assertEquals(1L, repository.playlistRevision.value)
                if (failVod || malformedVod) {
                    assertEquals(oldVod, vodFile.readText())
                    assertEquals(oldSeries, seriesFile.readText())
                } else {
                    val vod = CatalogueDiskFile.read(vodFile) { JsonParser.parseReader(it) }.asJsonObject.getAsJsonArray("items")
                    val series = CatalogueDiskFile.read(seriesFile) { JsonParser.parseReader(it) }.asJsonObject.getAsJsonArray("items")
                    assertEquals(if (emptyVod) 0 else 1, vod.size())
                    assertEquals(if (emptyVod) 0 else 1, series.size())
                    if (!emptyVod) {
                        assertEquals(23, vod[0].asJsonObject["stream_id"].asInt)
                        assertEquals(33, series[0].asJsonObject["series_id"].asInt)
                    }
                }
            }
            assertTrue(actions.none { it == "get_series_info" })
            assertTrue(actions.groupingBy { it }.eachCount().values.all { it == if (recheck) 2 else 1 })
        } finally {
            @Suppress("UNCHECKED_CAST") val store = field("channelStore\$delegate").get(repository) as Lazy<IptvChannelStore>
            if (store.isInitialized()) store.value.close()
            @Suppress("UNCHECKED_CAST") val index = field("epgIndex\$delegate").get(repository) as Lazy<IptvEpgIndex>
            if (index.isInitialized()) index.value.close()
        }
    }
}
