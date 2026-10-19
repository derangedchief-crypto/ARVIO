package com.arflix.tv.data.repository

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
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
class EpisodeVodVerificationTest {
    private val show = """[{"series_id":41,"name":"Harbour Watch","tmdb":"12345"}]"""
    private val episode = """{"episodes":{"1":[{"id":502,"episode_num":2,"season":1,"title":"Harbour Watch S01E02","container_extension":"mp4"}]}}"""
    @Test fun exactEpisodeFoundInNativeSeriesCatalogue() = fixture()
    @Test fun sameEpisodeNumberInWrongSeasonIsNotAccepted() = fixture(season = 2, missing = true)
    @Test fun existingShowWithoutRequestedEpisodeConfirmsAbsence() = fixture(info = """{"episodes":{"1":[{"id":501,"episode_num":1,"season":1,"title":"First"}]}}""", missing = true)
    @Test fun absentShowAndAbsentVodFilesConfirmMissing() = fixture(series = listOf("[]"), missing = true)
    @Test fun failedEpisodeInfoIsNotMisreportedAsAbsent() = fixture(info = "unavailable", unavailable = true)
    @Test fun malformedEpisodeNumberingBlocksPaidFallback() = fixture(info = """{"episodes":{"1":[{"id":502,"title":"Untyped episode"}]}}""", unavailable = true)
    @Test fun secondProviderEpisodeStillWinsWhenFirstProviderHasNoShow() = fixture(series = listOf("[]", show), host = "p1.example.test")
    @Test fun failedProviderCannotHideAnotherProvidersEpisode() = fixture(series = listOf("unavailable", show), host = "p1.example.test")
    @Test fun everyMatchingQualityVariantIsCheckedForTheEpisode() = fixture(
        series = listOf("""[{"series_id":41,"name":"Harbour Watch HD","tmdb":"12345"},{"series_id":42,"name":"Harbour Watch 4K","tmdb":"12345"}]"""), alternateVariant = true)
    @Test fun contradictorySeasonNumberingBlocksFallback() = fixture(
        info = """{"episodes":{"1":[{"id":502,"episode_num":2,"season":2,"title":"Episode 2"}]}}""", unavailable = true)
    @Test fun episodesStoredInMovieVodListAreAlsoChecked() = fixture(series = listOf("[]"),
        files = """[{"stream_id":77,"name":"Harbour Watch S01E02","tmdb":"12345","container_extension":"mp4"}]""", fileEpisode = true)
    @Test fun ambiguousSeasonDoesNotBecomeAConfirmedAbsence() = fixture(series = listOf("[]"),
        files = """[{"stream_id":77,"name":"Harbour Watch E02","tmdb":"12345","container_extension":"mp4"}]""", unavailable = true)

    private fun fixture(series: List<String> = listOf(show), info: String = episode,
        files: String = "[]", season: Int = 1, missing: Boolean = false, unavailable: Boolean = false,
        host: String = "p0.example.test", fileEpisode: Boolean = false, alternateVariant: Boolean = false) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val playlists = series.indices.map { index -> IptvPlaylistEntry("provider-$index", "Provider $index",
            "https://p$index.example.test/get.php?username=fixture-user&password=fixture-pass&type=m3u_plus", "") }
        val dir = File(context.filesDir, "xtream_vod_disk_cache").apply { mkdirs() }
        series.indices.forEach { index ->
            val owner = "https://p$index.example.test|fixture-user|fixture-pass"
            val hash = MessageDigest.getInstance("MD5").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
            listOf("vod", "series").forEach { kind ->
                File(dir, "${kind}_$hash.json").writeText("{\"savedAtMs\":${System.currentTimeMillis()},\"items\":[]}")
            }
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val index = chain.request().url.host.substringAfter('p').substringBefore('.').toInt()
            val body = when (chain.request().url.queryParameter("action")) {
                "get_series" -> series[index]
                "get_series_info" -> if (alternateVariant && chain.request().url.queryParameter("series_id") == "41")
                    """{"episodes":{"1":[{"id":501,"episode_num":1,"season":1,"title":"First"}]}}""" else info
                "get_vod_streams" -> files
                else -> error("Unexpected episode verification endpoint")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body == "unavailable") 503 else 200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true), IptvEpgPlaybackGate()))
        every { repository.observeConfig() } returns flowOf(IptvConfig(playlists = playlists))
        try {
            val result = repository.verifyEpisodeVodBeforeFallback("Harbour Watch", season, 2, tmdbId = 12345)
            assertFalse("Unavailable check cannot become an empty result", unavailable)
            if (missing) assertTrue(result.isEmpty())
            else assertTrue(result.first().url.orEmpty().contains(
                if (fileEpisode) "$host/movie/fixture-user/fixture-pass/77.mp4" else "$host/series/fixture-user/fixture-pass/502.mp4"))
            val afterFirst = requests
            assertEquals(result, repository.verifyEpisodeVodBeforeFallback("Harbour Watch", season, 2, tmdbId = 12345))
            assertEquals("Confirmed results must not re-fetch during the same check", afterFirst, requests)
        } catch (error: VodVerificationException) {
            if (!unavailable) throw error
        }
    }
}
