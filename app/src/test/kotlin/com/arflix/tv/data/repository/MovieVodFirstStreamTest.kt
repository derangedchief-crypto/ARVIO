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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
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
    @Test fun explicitlyRequestedCloudSearchRemainsAvailableWithMovieVod() = fixture(found = true, manual = true)
    @Test fun explicitlyRequestedCloudSearchCanBypassFailedMovieVodCheck() = fixture(found = false, unavailable = true, manual = true)
    @Test fun explicitlyRequestedCloudSearchRemainsAvailableWithEpisodeVod() = fixture(found = true, isEpisode = true, manual = true)
    @Test fun nonProgressiveMovieCloudSearchHonoursExplicitChoice() = fixture(found = true, nonProgressive = true, manual = true)
    @Test fun nonProgressiveEpisodeCloudSearchHonoursExplicitChoice() = fixture(found = true, nonProgressive = true, isEpisode = true, manual = true)
    @Test fun manualMovieSearchDoesNotDisableVodFirstForNextRequest() = fixture(found = true, manual = true, checkReturnToVod = true)
    @Test fun manualEpisodeSearchDoesNotDisableVodFirstForNextRequest() = fixture(found = true, manual = true, isEpisode = true, checkReturnToVod = true)
    @Test fun unauthorizedManualMovieSearchDoesNotCallSources() = fixture(found = true, manual = true, cloudAccess = false)
    @Test fun unauthorizedManualEpisodeSearchDoesNotCallSources() = fixture(found = true, manual = true, isEpisode = true, cloudAccess = false)
    @Test fun unauthorizedNonProgressiveMovieSearchDoesNotCallSources() = fixture(found = true, manual = true, nonProgressive = true, cloudAccess = false)
    @Test fun unauthorizedNonProgressiveEpisodeSearchDoesNotCallSources() = fixture(found = true, manual = true, isEpisode = true, nonProgressive = true, cloudAccess = false)
    @Test fun unreadableCloudAccessFailsClosedWithoutCallingSources() = fixture(found = true, manual = true, cloudAccess = false, cloudStateFailure = true)
    @Test fun cloudVisibilityObservesExistingEntitlementRevocation() = fixture(found = true, manual = true, observeRevocation = true)

    private fun fixture(found: Boolean, unavailable: Boolean = false,
        cancelLookup: Boolean = false, nonProgressive: Boolean = false, isEpisode: Boolean = false,
        manual: Boolean = false, checkReturnToVod: Boolean = false, cloudAccess: Boolean = true,
        cloudStateFailure: Boolean = false, observeRevocation: Boolean = false) = runBlocking { kotlinx.coroutines.supervisorScope {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var verified = false
        val verificationCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val pointsRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val iptv = mockk<IptvRepository>()
        coEvery { iptv.findMovieVodSources(any(), any(), any(), any(), true, true) } coAnswers {
            verificationCalls.incrementAndGet()
            started.complete(Unit)
            release.await()
            if (unavailable) throw VodVerificationException()
            verified = true
            if (found) listOf(vod) else emptyList()
        }
        coEvery { iptv.verifyEpisodeVodBeforeFallback(any(), any(), any(), any(), any()) } coAnswers {
            verificationCalls.incrementAndGet()
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
        val entitlementPrefs = RuntimeEnvironment.getApplication().getSharedPreferences("arvio_entitlement_guard", 0)
        entitlementPrefs.edit().clear().apply()
        if (cloudAccess) entitlementPrefs.edit().putString("granted_addon_id:cloud_stream", "paid-fixture").commit()
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
            .set(repository, if (cloudStateFailure) kotlinx.coroutines.flow.flow<List<Addon>> { throw java.io.IOException("Unreadable auth state") }
                else flowOf(listOf(addon)))
        try {
            if (cloudStateFailure) assertFalse(repository.extremeCloudAccess.first())
            if (observeRevocation) {
                val observed = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val observer = launch {
                    repository.extremeCloudAccess.distinctUntilChanged().take(2).collect { observed.send(it) }
                }
                assertTrue(kotlinx.coroutines.withTimeout(5_000) { observed.receive() })
                entitlementPrefs.edit().remove("granted_addon_id:cloud_stream").commit()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(kotlinx.coroutines.withTimeout(5_000) { observed.receive() })
                observer.join()
                entitlementPrefs.edit().putString("granted_addon_id:cloud_stream", "paid-fixture").commit()
            }
            val mode = if (manual) SourceSearchMode.ManualScraperFallback else SourceSearchMode.VodFirst
            if (manual) { verified = true; release.complete(Unit) }
            val result = async {
                if (isEpisode && nonProgressive) repository.resolveEpisodeStreams("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch", searchMode = mode).streams
                else if (isEpisode) repository.resolveEpisodeStreamsProgressive("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch", searchMode = mode).toList().last().streams
                else if (nonProgressive) repository.resolveMovieStreams("tt1234567", "The Quiet Lake", 2026, tmdbId = 12345, searchMode = mode).streams
                else repository.resolveMovieStreamsProgressive("tt1234567", "The Quiet Lake", 2026, tmdbId = 12345, searchMode = mode)
                    .toList().last().streams
            }
            if (!manual) { started.await(); assertEquals(0, pointsRequests.get()) }
            if (cancelLookup) {
                result.cancelAndJoin()
                release.complete(Unit)
            } else {
                release.complete(Unit)
                try {
                    val sources = result.await()
                    assertFalse(unavailable && !manual)
                    assertEquals(if (found && !manual) listOf(vod) else emptyList<StreamSource>(), sources)
                } catch (error: VodVerificationException) {
                    if (!unavailable) throw error
                }
            }
            if (checkReturnToVod) {
                val automatic = if (isEpisode) repository.resolveEpisodeStreamsProgressive("tt1234567", 1, 2,
                    tmdbId = 12345, title = "Harbour Watch").toList().last().streams
                else repository.resolveMovieStreamsProgressive("tt1234567", "The Quiet Lake", 2026,
                    tmdbId = 12345).toList().last().streams
                assertEquals(listOf(vod), automatic)
            }
            assertEquals(if (manual) (if (cloudAccess) 1 else 0) else if (found || unavailable || cancelLookup) 0 else 1, pointsRequests.get())
            assertEquals(if (manual && !checkReturnToVod) 0 else 1, verificationCalls.get())
        } finally {
            val scope = StreamRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }
            (scope.get(repository) as CoroutineScope).cancel()
        }
    } }
}
