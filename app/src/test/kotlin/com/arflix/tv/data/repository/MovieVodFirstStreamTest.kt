package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonManifest
import com.arflix.tv.data.model.AddonResource
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.StreamSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class MovieVodFirstStreamTest {
    private val vod = StreamSource(source = "Movie VOD", addonName = "IPTV VOD", addonId = "iptv_xtream_vod",
        quality = "1080p", size = "", url = "https://provider.example.test/movie/55.mp4")

    @Test fun progressiveLookupNeverCallsScraperWhenVodHasMovie() = fixture(found = true)
    @Test fun scraperWaitsUntilCatalogueVerificationCompletes() = fixture(found = false)
    @Test fun failedVerificationDoesNotSpendScraperPoints() = fixture(found = false, unavailable = true)
    @Test fun cancelledVerificationDoesNotStartScraperLater() = fixture(found = false, cancelLookup = true)
    @Test fun nonProgressiveLookupAlsoPrefersVodWithoutCallingScraper() = fixture(found = true, nonProgressive = true)
    @Test fun episodeVodHitDoesNotSpendScraperPoints() = fixture(found = true, isEpisode = true)
    @Test fun episodeScraperWaitsForConfirmedEpisodeAbsence() = fixture(found = false, isEpisode = true)
    @Test fun episodeVerificationFailureDoesNotSpendPoints() = fixture(found = false, unavailable = true, isEpisode = true)
    @Test fun cancelledEpisodeCheckCannotStartPaidFallback() = fixture(found = false, cancelLookup = true, isEpisode = true)
    @Test fun nonProgressiveEpisodeLookupAlsoUsesVodFirst() = fixture(found = true, nonProgressive = true, isEpisode = true)

    private fun fixture(found: Boolean, unavailable: Boolean = false,
        cancelLookup: Boolean = false, nonProgressive: Boolean = false, isEpisode: Boolean = false) = runBlocking { kotlinx.coroutines.supervisorScope {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var verified = false
        val pointsRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val iptv = mockk<IptvRepository>()
        coEvery { iptv.findMovieVodSources(any(), any(), any(), any(), true, true) } coAnswers {
            started.complete(Unit)
            release.await()
            if (unavailable) throw VodVerificationException()
            verified = true
            if (found) listOf(vod) else emptyList()
        }
        coEvery { iptv.verifyEpisodeVodBeforeFallback(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            if (unavailable) throw VodVerificationException()
            verified = true
            if (found) listOf(vod) else emptyList()
        }
        val runtime = mockk<HttpLocalScraperRuntime>(relaxed = true)
        every { runtime.canHandle(any()) } returns true
        coEvery { runtime.resolveMovieStreams(any(), any(), any(), any()) } coAnswers {
            assertTrue("Scraper ran before VOD was verified", verified)
            pointsRequests.incrementAndGet()
            emptyList()
        }
        coEvery { runtime.resolveEpisodeStreams(any(), any(), any(), any(), any(), any()) } coAnswers {
            assertTrue("Episode scraper ran before VOD was verified", verified)
            pointsRequests.incrementAndGet()
            emptyList()
        }
        val profileId = java.util.UUID.randomUUID().toString()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns profileId
        every { profile.activeProfileId } returns MutableStateFlow(profileId)
        val repository = spyk(StreamRepository(RuntimeEnvironment.getApplication(), mockk(relaxed = true),
            OkHttpClient(), profile, mockk(relaxed = true), iptv, runtime, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)))
        val addon = Addon("paid-fixture", "Points source", "1", "Fixture", true, type = AddonType.COMMUNITY,
            url = "https://scraper.example.test/manifest.json",
            manifest = AddonManifest("paid-fixture", "Points source", "1", types = listOf("movie", "series"),
                resources = listOf(AddonResource("stream", types = listOf("movie", "series")))))
        StreamRepository::class.java.getDeclaredField("installedAddons").apply { isAccessible = true }
            .set(repository, flowOf(listOf(addon)))
        try {
            val result = async {
                if (isEpisode && nonProgressive) repository.resolveEpisodeStreams("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch").streams
                else if (isEpisode) repository.resolveEpisodeStreamsProgressive("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch").toList().last().streams
                else if (nonProgressive) repository.resolveMovieStreams("tt1234567", "The Quiet Lake", 2026, tmdbId = 12345).streams
                else repository.resolveMovieStreamsProgressive("tt1234567", "The Quiet Lake", 2026, tmdbId = 12345)
                    .toList().last().streams
            }
            started.await()
            assertEquals(0, pointsRequests.get())
            if (cancelLookup) {
                result.cancelAndJoin()
                release.complete(Unit)
            } else {
                release.complete(Unit)
                try {
                    val sources = result.await()
                    assertFalse(unavailable)
                    assertEquals(if (found) listOf(vod) else emptyList<StreamSource>(), sources)
                } catch (error: VodVerificationException) {
                    if (!unavailable) throw error
                }
            }
            assertEquals(if (found || unavailable || cancelLookup) 0 else 1, pointsRequests.get())
        } finally {
            val scope = StreamRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }
            (scope.get(repository) as CoroutineScope).cancel()
        }
    } }
}
