package com.arflix.tv.data.repository

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
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
class MovieVodVerificationTest {
    private val movie = """[{"stream_id":55,"name":"The Quiet Lake (2026)","tmdb":"12345","container_extension":"mp4"}]"""
    @Test fun explicitPlaylistProviderIsUsedWithoutLegacyUrls() = fixture(listOf(movie))
    @Test fun masterplan2026MatchesProviderMasterPlanSpelling() = fixture(
        listOf("""[{"stream_id":55,"name":"Master Plan (2026)","container_extension":"mp4"}]"""), title = "Masterplan", year = 2026, tmdb = null)
    @Test fun masterplan2026MatchesExactProviderTitleWithoutIds() = fixture(
        listOf("""[{"stream_id":55,"name":"Masterplan (2026) [4K]","container_extension":"mp4"}]"""), title = "Masterplan", year = 2026, tmdb = null)
    @Test fun masterplanSequelIsNotAcceptedAsRequestedMovie() = fixture(
        listOf("""[{"stream_id":55,"name":"Master Plan 2 (2026)","container_extension":"mp4"}]"""), title = "Masterplan", year = 2026, tmdb = null, expectMissing = true)
    @Test fun staleCachedMissIsRefreshedBeforeDeclaringAbsence() = fixture(listOf(movie), seed = listOf("[]"))
    @Test fun everyEnabledProviderIsCheckedBeforeMissing() = fixture(listOf("[]", movie), expectedHost = "p1.example.test")
    @Test fun cachedHitOnSecondProviderDoesNotWaitForFirstProvidersNetwork() = fixture(
        listOf("[]", "[]"), seed = listOf("[]", movie), expectedHost = "p1.example.test", expectedRequests = 0)
    @Test fun unavailableProviderIsNotMisreportedAsMissing() = fixture(listOf("unavailable"), unavailable = true)
    @Test fun malformedCatalogueIsNotMisreportedAsMissing() = fixture(listOf("{\"error\":\"Unavailable\"}"), unavailable = true)
    @Test fun failedFirstProviderCannotHideSecondProvidersMovie() = fixture(
        listOf("unavailable", movie), expectedHost = "p1.example.test")
    @Test fun validEmptyCatalogueConfirmsMissingAndCoalescesRepeatedChecks() = fixture(listOf("[]"), expectMissing = true)
    @Test fun shortTitleWithoutMetadataIdsStillMatches() = fixture(
        listOf("""[{"stream_id":55,"name":"Up 2009","container_extension":"mp4"}]"""), title = "Up", year = 2009, tmdb = null)
    @Test fun accentedProviderTitleMatchesUnaccentedMetadata() = fixture(
        listOf("""[{"stream_id":55,"name":"Amélie (2001)","container_extension":"mp4"}]"""), title = "Amelie", year = 2001, tmdb = null)
    @Test fun wrongRemakeYearIsNotAcceptedAsVodHit() = fixture(
        listOf("""[{"stream_id":55,"name":"Dune (1984)","container_extension":"mp4"}]"""), title = "Dune", year = 2021, tmdb = null, expectMissing = true)

    private fun fixture(bodies: List<String>, seed: List<String> = bodies.map { "[]" },
        title: String = "The Quiet Lake", year: Int = 2026, tmdb: Int? = 12345,
        unavailable: Boolean = false, expectMissing: Boolean = false,
        expectedHost: String = "p0.example.test", expectedRequests: Int = bodies.size) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("iptv_movie_source_cache_v1", 0).edit().clear().commit()
        val playlists = bodies.indices.map { index -> IptvPlaylistEntry("provider-$index", "Provider $index",
            "https://p$index.example.test/get.php?username=fixture-user&password=fixture-pass&type=m3u_plus", "") }
        val dir = File(context.filesDir, "xtream_vod_disk_cache").apply { mkdirs() }
        bodies.indices.forEach { index ->
            val owner = "https://p$index.example.test|fixture-user|fixture-pass"
            val hash = MessageDigest.getInstance("MD5").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
            File(dir, "vod_$hash.json").writeText("{\"savedAtMs\":${System.currentTimeMillis()},\"items\":${seed[index]}}")
        }
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("get_vod_streams", chain.request().url.queryParameter("action"))
            val host = chain.request().url.host
            requested.add(host)
            val index = host.substringAfter('p').substringBefore('.').toInt()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (bodies[index] == "unavailable") 503 else 200).message("Fixture")
                .body(bodies[index].toResponseBody()).build()
        }.build()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val repository = spyk(IptvRepository(context, client, profile, mockk(relaxed = true), IptvEpgPlaybackGate()))
        every { repository.observeConfig() } returns flowOf(IptvConfig(playlists = playlists))
        try {
            try {
                val result = repository.findMovieVodSources(title, year, tmdbId = tmdb, verifyBeforeFallback = true)
                assertFalse("Failed lookup must not return a verified miss", unavailable)
                if (expectMissing) {
                    assertTrue(result.isEmpty())
                    assertTrue(repository.findMovieVodSources(title, year, tmdbId = tmdb, verifyBeforeFallback = true).isEmpty())
                } else {
                    assertTrue(result.isNotEmpty())
                    assertTrue(result.first().url.orEmpty().contains("$expectedHost/movie/fixture-user/fixture-pass/55.mp4"))
                }
            } catch (error: VodVerificationException) {
                if (!unavailable) throw error
            }
            assertEquals(expectedRequests, requested.size)
        } finally {
            val scope = IptvRepository::class.java.getDeclaredField("iptvCacheScope").apply { isAccessible = true }
            (scope.get(repository) as CoroutineScope).cancel()
        }
    }
}
