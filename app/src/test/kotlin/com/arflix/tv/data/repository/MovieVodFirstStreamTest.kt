package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    private val vod = StreamSource("Masterplan (2026)", "IPTV VOD", "iptv_xtream_vod", "1080p", "",
        url = "https://provider.example.test/movie/55.mp4")
    private val cloud = StreamSource("Masterplan.2026.1080p", "Torrentio PM", "paid-fixture", "1080p", "4 GB",
        url = "https://cloud.example.test/movie.mp4", behaviorHints = StreamBehaviorHints(cached = true))

    @Test fun movieVodHitNeverStartsCloud() = fixture(found = true)
    @Test fun movieMissAutomaticallySearchesAuthorizedCloudAfterVerification() = fixture()
    @Test fun movieVerificationFailureDoesNotStartCloud() = fixture(unavailable = true)
    @Test fun movieVerificationCancellationDoesNotStartCloud() = fixture(cancelLookup = true)
    @Test fun nonProgressiveMovieHitUsesVod() = fixture(found = true, nonProgressive = true)
    @Test fun nonProgressiveMovieMissAutomaticallyUsesAuthorizedCloud() = fixture(nonProgressive = true)
    @Test fun episodeVodHitNeverStartsCloud() = fixture(found = true, isEpisode = true)
    @Test fun episodeMissAutomaticallySearchesAuthorizedCloud() = fixture(isEpisode = true)
    @Test fun episodeVerificationFailureDoesNotStartCloud() = fixture(unavailable = true, isEpisode = true)
    @Test fun episodeVerificationCancellationDoesNotStartCloud() = fixture(cancelLookup = true, isEpisode = true)
    @Test fun nonProgressiveEpisodeHitUsesVod() = fixture(found = true, nonProgressive = true, isEpisode = true)
    @Test fun nonProgressiveEpisodeMissAutomaticallyUsesAuthorizedCloud() = fixture(nonProgressive = true, isEpisode = true)
    @Test fun unauthorizedMovieMissMakesNoCloudRequests() = fixture(cloudAccess = false)
    @Test fun unauthorizedEpisodeMissMakesNoCloudRequests() = fixture(isEpisode = true, cloudAccess = false)
    @Test fun unauthorizedNonProgressiveMovieMissMakesNoCloudRequests() = fixture(nonProgressive = true, cloudAccess = false)
    @Test fun unauthorizedNonProgressiveEpisodeMissMakesNoCloudRequests() = fixture(nonProgressive = true, isEpisode = true, cloudAccess = false)
    @Test fun vodIsStillAvailableWithoutCloudAccess() = fixture(found = true, cloudAccess = false)
    @Test fun unreadableCloudAuthFailsClosed() = fixture(cloudAccess = false, cloudStateFailure = true)
    @Test fun vodDoesNotDependOnReadableCloudAuth() = fixture(found = true, cloudAccess = false, cloudStateFailure = true)
    @Test fun cachedMovieLinksAreStillBrandedExtremeCloud() = fixture(repeatLookup = true)
    @Test fun cachedEpisodeLinksAreStillBrandedExtremeCloud() = fixture(isEpisode = true, repeatLookup = true)
    @Test fun cachedNonProgressiveMovieLinksAreBrandedExtremeCloud() = fixture(nonProgressive = true, repeatLookup = true)
    @Test fun cachedNonProgressiveEpisodeLinksAreBrandedExtremeCloud() = fixture(nonProgressive = true, isEpisode = true, repeatLookup = true)
    @Test fun revokedMovieAccessCannotReuseCachedCloudLinks() = fixture(revokeAfterFirst = true)
    @Test fun revokedEpisodeAccessCannotReuseCachedCloudLinks() = fixture(isEpisode = true, revokeAfterFirst = true)
    @Test fun cloudVisibilityTracksExistingEntitlementRevocation() = fixture(found = true, observeRevocation = true)

    private fun fixture(found: Boolean = false, unavailable: Boolean = false, cancelLookup: Boolean = false,
        nonProgressive: Boolean = false, isEpisode: Boolean = false, cloudAccess: Boolean = true,
        cloudStateFailure: Boolean = false, repeatLookup: Boolean = false, revokeAfterFirst: Boolean = false,
        observeRevocation: Boolean = false) = runBlocking { supervisorScope {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var verified = false
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val iptv = mockk<IptvRepository>()
        suspend fun lookup(): List<StreamSource> {
            started.complete(Unit)
            release.await()
            if (unavailable) throw VodVerificationException()
            verified = true
            return if (found) listOf(vod) else emptyList()
        }
        coEvery { iptv.findMovieVodSources(any(), any(), any(), any(), true, true) } coAnswers { lookup() }
        coEvery { iptv.verifyEpisodeVodBeforeFallback(any(), any(), any(), any(), any()) } coAnswers { lookup() }
        val runtime = mockk<HttpLocalScraperRuntime>(relaxed = true)
        every { runtime.canHandle(any()) } returns true
        coEvery { runtime.resolveMovieStreams(any(), any(), any(), any()) } coAnswers {
            assertTrue("Cloud started before VOD completed", verified); calls.incrementAndGet(); listOf(cloud)
        }
        coEvery { runtime.resolveEpisodeStreams(any(), any(), any(), any(), any(), any()) } coAnswers {
            assertTrue("Cloud started before episode verification", verified); calls.incrementAndGet(); listOf(cloud)
        }
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("arvio_entitlement_guard", 0)
        prefs.edit().clear().commit()
        if (cloudAccess) prefs.edit().putString("granted_addon_id:cloud_stream", "paid-fixture").commit()
        val profileId = java.util.UUID.randomUUID().toString()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns profileId
        every { profile.activeProfileId } returns MutableStateFlow(profileId)
        val repository = spyk(StreamRepository(context, mockk(relaxed = true), OkHttpClient(), profile,
            mockk(relaxed = true), iptv, runtime, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)))
        val addon = Addon("paid-fixture", "Torrentio PM", "1", "Fixture", true, type = AddonType.COMMUNITY,
            url = "https://scraper.example.test/manifest.json",
            manifest = AddonManifest("paid-fixture", "Torrentio PM", "1", types = listOf("movie", "series"),
                resources = listOf(AddonResource("stream", types = listOf("movie", "series")))))
        StreamRepository::class.java.getDeclaredField("installedAddons").apply { isAccessible = true }.set(repository,
            if (cloudStateFailure) flow<List<Addon>> { throw java.io.IOException("Unreadable auth") } else flowOf(listOf(addon)))
        suspend fun resolve(): List<StreamSource> = when {
            isEpisode && nonProgressive -> repository.resolveEpisodeStreams("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch").streams
            isEpisode -> repository.resolveEpisodeStreamsProgressive("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch").toList().last().streams
            nonProgressive -> repository.resolveMovieStreams("tt1234567", "Masterplan", 2026, tmdbId = 12345).streams
            else -> repository.resolveMovieStreamsProgressive("tt1234567", "Masterplan", 2026, tmdbId = 12345).toList().last().streams
        }
        try {
            if (observeRevocation) {
                val observed = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val observer = launch { repository.extremeCloudAccess.distinctUntilChanged().take(2).collect { observed.send(it) } }
                assertTrue(withTimeout(5_000) { observed.receive() })
                prefs.edit().remove("granted_addon_id:cloud_stream").commit()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(withTimeout(5_000) { observed.receive() })
                observer.join()
                prefs.edit().putString("granted_addon_id:cloud_stream", "paid-fixture").commit()
            }
            val result = async { resolve() }
            started.await()
            assertEquals(0, calls.get())
            if (cancelLookup) { result.cancelAndJoin(); release.complete(Unit) }
            else {
                release.complete(Unit)
                try {
                    val sources = result.await()
                    assertFalse(unavailable)
                    assertEquals(if (found) listOf(vod) else if (cloudAccess) listOf(cloud.copy(displayAddonName = "Extreme Cloud")) else emptyList<StreamSource>(), sources)
                    if (repeatLookup) assertEquals(sources, resolve())
                    if (revokeAfterFirst) {
                        prefs.edit().remove("granted_addon_id:cloud_stream").commit()
                        assertTrue(resolve().isEmpty())
                    }
                } catch (error: VodVerificationException) { if (!unavailable) throw error }
            }
            assertEquals(if (found || unavailable || cancelLookup || !cloudAccess) 0 else 1, calls.get())
        } finally {
            val field = StreamRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }
            (field.get(repository) as CoroutineScope).cancel()
        }
    } }
}
