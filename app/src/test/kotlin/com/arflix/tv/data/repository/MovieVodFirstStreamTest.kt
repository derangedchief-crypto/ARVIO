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

    @Test fun authorizedMovieVodHitKeepsCloudBackupsAvailable() = fixture(found = true)
    @Test fun movieMissAutomaticallySearchesAuthorizedCloudAfterVerification() = fixture()
    @Test fun authorizedMovieLookupFailureStillOffersCloudBackups() = fixture(unavailable = true)
    @Test fun movieVerificationCancellationDoesNotStartCloud() = fixture(cancelLookup = true)
    @Test fun nonProgressiveMovieHitUsesVod() = fixture(found = true, nonProgressive = true)
    @Test fun nonProgressiveMovieMissAutomaticallyUsesAuthorizedCloud() = fixture(nonProgressive = true)
    @Test fun authorizedEpisodeVodHitKeepsCloudBackupsAvailable() = fixture(found = true, isEpisode = true)
    @Test fun episodeMissAutomaticallySearchesAuthorizedCloud() = fixture(isEpisode = true)
    @Test fun authorizedEpisodeLookupFailureStillOffersCloudBackups() = fixture(unavailable = true, isEpisode = true)
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
    @Test fun cachedCloudBackupsDoNotReplaceMovieVod() = fixture(found = true, repeatLookup = true)
    @Test fun cachedCloudBackupsDoNotReplaceEpisodeVod() = fixture(found = true, isEpisode = true, repeatLookup = true)
    @Test fun revokedAccessRemovesCloudButKeepsVod() = fixture(found = true, revokeAfterFirst = true)
    @Test fun unauthorizedMovieFailureMakesNoCloudRequest() = fixture(unavailable = true, cloudAccess = false)
    @Test fun unauthorizedEpisodeFailureMakesNoCloudRequest() = fixture(unavailable = true, isEpisode = true, cloudAccess = false)
    @Test fun movieVodIsPublishedBeforeSlowCloudBackupsFinish() = fixture(found = true, holdCloud = true)
    @Test fun episodeVodIsPublishedBeforeSlowCloudBackupsFinish() = fixture(found = true, isEpisode = true, holdCloud = true)
    @Test fun cloudFailureDoesNotRemoveVod() = fixture(found = true, cloudFails = true)
    @Test fun detailsCanShareMovieVodLookupWithSourceDiscovery() = fixture(found = true, prechecked = true)
    @Test fun detailsCanShareEpisodeVodLookupWithSourceDiscovery() = fixture(found = true, isEpisode = true, prechecked = true)
    @Test fun invalidPrimaryCannotStartMovieCloudFallback()=fixture(primaryInitiallyActive=false)
    @Test fun invalidPrimaryCannotStartEpisodeCloudFallback()=fixture(primaryInitiallyActive=false,isEpisode=true)
    @Test fun invalidPrimaryCannotReturnNonProgressiveCloudSources()=fixture(primaryInitiallyActive=false,nonProgressive=true)
    @Test fun primaryFailureDuringVodDoesNotTurnIntoCloudFallback()=fixture(invalidateInVod=true)
    @Test fun primaryFailureDuringCloudCannotPublishLinks()=fixture(invalidateDuringCloud=true)
    @Test fun primaryFailureDuringEpisodeCloudCannotPublishLinks()=fixture(invalidateDuringCloud=true,isEpisode=true)
    @Test fun expiredPrimaryCannotReuseCachedMovieCloudSources()=fixture(denyAfterFirst=true,nonProgressive=true)
    @Test fun expiredPrimaryCannotReuseCachedEpisodeCloudSources()=fixture(denyAfterFirst=true,isEpisode=true,nonProgressive=true)
    @Test fun expiredPrimaryCannotReuseResolvedDirectCloudUrl()=fixture(resolvedCacheMode=true)
    @Test fun cloudVisibilityTracksPrimaryInvalidationWithoutRemovingTheAddon()=fixture(observePrimaryRevocation=true)

    private fun fixture(found: Boolean = false, unavailable: Boolean = false, cancelLookup: Boolean = false,
        nonProgressive: Boolean = false, isEpisode: Boolean = false, cloudAccess: Boolean = true,
        cloudStateFailure: Boolean = false, repeatLookup: Boolean = false, revokeAfterFirst: Boolean = false,
        observeRevocation: Boolean = false, holdCloud: Boolean = false, cloudFails: Boolean = false,
        prechecked: Boolean = false,primaryInitiallyActive:Boolean=true,invalidateInVod:Boolean=false,
        invalidateDuringCloud:Boolean=false,denyAfterFirst:Boolean=false,resolvedCacheMode:Boolean=false,
        observePrimaryRevocation:Boolean=false) = runBlocking { supervisorScope {
        val primaryActive=java.util.concurrent.atomic.AtomicBoolean(primaryInitiallyActive)
        val primaryState=MutableStateFlow(com.arflix.tv.access.PrimaryAccessState(checking=false,bound=true,primaryAllowed=primaryInitiallyActive))
        fun invalidate(){primaryActive.set(false);primaryState.value=primaryState.value.copy(primaryAllowed=false)}
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cloudStarted = CompletableDeferred<Unit>()
        val cloudRelease = CompletableDeferred<Unit>()
        val emissions = kotlinx.coroutines.channels.Channel<ProgressiveStreamResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val lookups = java.util.concurrent.atomic.AtomicInteger(0)
        var verified = false
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val iptv = mockk<IptvRepository>()
        fun checkPrimary():Long {if(!primaryActive.get())throw com.arflix.tv.access.PrimaryPlaybackDeniedException();return 1L}
        every{iptv.requirePrimaryPlayback(null)}answers{checkPrimary()}
        every{iptv.requirePrimaryPlayback(1L)}answers{checkPrimary()}
        every{iptv.primaryPlaybackAllowed()}answers{primaryActive.get()}
        every{iptv.primaryAccessState}returns primaryState
        coEvery { iptv.vodLookupScopeKey() } returns "fixture-vod-account"
        suspend fun lookup(): List<StreamSource> {
            lookups.incrementAndGet()
            started.complete(Unit)
            release.await()
            verified = true
            if(invalidateInVod){invalidate();throw VodVerificationException()}
            if (unavailable) throw VodVerificationException()
            return if (found) listOf(vod) else emptyList()
        }
        coEvery { iptv.findMovieVodSources(any(), any(), any(), any(), true, true) } coAnswers { lookup() }
        coEvery { iptv.verifyEpisodeVodBeforeFallback(any(), any(), any(), any(), any()) } coAnswers { lookup() }
        val runtime = mockk<HttpLocalScraperRuntime>(relaxed = true)
        every { runtime.canHandle(any()) } returns true
        suspend fun cloudResults(): List<StreamSource> {
            assertTrue("Cloud started before VOD lookup finished", verified)
            calls.incrementAndGet()
            cloudStarted.complete(Unit)
            if (holdCloud) cloudRelease.await()
            if (cloudFails) throw java.io.IOException("Cloud temporarily unavailable")
            if(invalidateDuringCloud)invalidate()
            return listOf(cloud)
        }
        coEvery { runtime.resolveMovieStreams(any(), any(), any(), any()) } coAnswers {
            cloudResults()
        }
        coEvery { runtime.resolveEpisodeStreams(any(), any(), any(), any(), any(), any()) } coAnswers {
            cloudResults()
        }
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("arvio_entitlement_guard", 0)
        prefs.edit().clear().commit()
        if (cloudAccess) prefs.edit().putString("granted_addon_id:cloud_stream", "paid-fixture").commit()
        val profileId = java.util.UUID.randomUUID().toString()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns profileId
        every { profile.activeProfileId } returns MutableStateFlow(profileId)
        val original = StreamRepository(context, mockk(relaxed = true), OkHttpClient(), profile,
            mockk(relaxed = true), iptv, runtime, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val repository = spyk(original)
        val addon = Addon("paid-fixture", "Torrentio PM", "1", "Fixture", true, type = AddonType.COMMUNITY,
            url = "https://scraper.example.test/manifest.json",
            manifest = AddonManifest("paid-fixture", "Torrentio PM", "1", types = listOf("movie", "series"),
                resources = listOf(AddonResource("stream", types = listOf("movie", "series")))))
        // Runtime adapter lambdas capture the original instance before spyk copies it.
        val installed = if (cloudStateFailure) flow<List<Addon>> { throw java.io.IOException("Unreadable auth") } else flowOf(listOf(addon))
        val addonField = StreamRepository::class.java.getDeclaredField("installedAddons").apply { isAccessible = true }
        addonField.set(repository, installed)
        addonField.set(original, installed)
        val known = if (prechecked) (if (found) listOf(vod) else emptyList()) else null
        suspend fun resolve(): List<StreamSource> = when {
            isEpisode && nonProgressive -> repository.resolveEpisodeStreams("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch", vodSources = known).streams
            isEpisode -> repository.resolveEpisodeStreamsProgressive("tt1234567", 1, 2, tmdbId = 12345, title = "Harbour Watch", vodSources = known).onEach { emissions.send(it) }.toList().last().streams
            nonProgressive -> repository.resolveMovieStreams("tt1234567", "Masterplan", 2026, tmdbId = 12345, vodSources = known).streams
            else -> repository.resolveMovieStreamsProgressive("tt1234567", "Masterplan", 2026, tmdbId = 12345, vodSources = known).onEach { emissions.send(it) }.toList().last().streams
        }
        try {
            if(!primaryInitiallyActive){
                try{resolve();fail("Invalid primary must deny Cloud discovery")}catch(_:com.arflix.tv.access.PrimaryPlaybackDeniedException){}
                assertEquals(0,calls.get());assertEquals(0,lookups.get());return@supervisorScope
            }
            if(resolvedCacheMode){
                assertNotNull(repository.resolveStreamForPlayback(cloud));invalidate()
                try{repository.resolveStreamForPlayback(cloud);fail("Resolved cache must not bypass primary access")}catch(_:com.arflix.tv.access.PrimaryPlaybackDeniedException){}
                return@supervisorScope
            }
            if(observePrimaryRevocation){
                val observed=kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val observer=launch{repository.extremeCloudAccess.distinctUntilChanged().take(2).collect{observed.send(it)}}
                assertTrue(withTimeout(5000){observed.receive()});invalidate();assertFalse(withTimeout(5000){observed.receive()});observer.join();return@supervisorScope
            }
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
            if (prechecked) { verified = true; release.complete(Unit) }
            val result = async { resolve() }
            if (!prechecked) { withTimeout(5_000) { started.await() }; assertEquals(0, calls.get()) }
            if (cancelLookup) { result.cancelAndJoin(); release.complete(Unit) }
            else {
                release.complete(Unit)
                if (holdCloud) {
                    val first = withTimeout(5_000) { emissions.receive() }
                    assertEquals(listOf(vod), first.streams)
                    assertFalse(first.isFinal)
                    withTimeout(5_000) { cloudStarted.await() }
                    assertFalse(result.isCompleted)
                    cloudRelease.complete(Unit)
                }
                try {
                    val sources = result.await()
                    assertFalse(invalidateInVod||invalidateDuringCloud)
                    assertFalse(unavailable && !cloudAccess)
                    val expectedVod = if (found && !unavailable) listOf(vod) else emptyList()
                    val expectedCloud = if (cloudAccess && !cloudFails) listOf(cloud.copy(displayAddonName = "Extreme Cloud")) else emptyList()
                    assertEquals(expectedVod + expectedCloud, sources)
                    if (repeatLookup) assertEquals(sources, resolve())
                    if (revokeAfterFirst) {
                        prefs.edit().remove("granted_addon_id:cloud_stream").commit()
                        assertEquals(expectedVod, resolve())
                    }
                    if(denyAfterFirst){invalidate();try{resolve();fail("Cached sources require a valid primary")}catch(_:com.arflix.tv.access.PrimaryPlaybackDeniedException){}}
                } catch (error: VodVerificationException) { if (!unavailable) throw error }
                catch(error:com.arflix.tv.access.PrimaryPlaybackDeniedException){if(!invalidateInVod&&!invalidateDuringCloud)throw error}
            }
            assertEquals(if (cancelLookup || !cloudAccess||invalidateInVod) 0 else 1, calls.get())
            if (prechecked) assertEquals(0, lookups.get())
        } finally {
            val field = StreamRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }
            (field.get(repository) as CoroutineScope).cancel()
        }
    } }
}
