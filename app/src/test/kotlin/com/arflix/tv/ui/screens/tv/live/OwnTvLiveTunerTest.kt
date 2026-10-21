// SPDX-License-Identifier: GPL-3.0-or-later
// Engine-port fixture adapted from OwnTV Core's LiveTuneControllerTest.
package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.player.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class OwnTvLiveTunerTest {
    private class Engines : LiveEngines {
        val opened = mutableListOf<Pair<String, LiveRequest>>()
        val order = mutableListOf<String>()
        var fallback: Pair<String, LiveRequest>? = null
        var reconnect: ReconnectUrlProvider? = null
        var failed = CompletableDeferred<String>()
        override var exoUrl: String? = null
        override val exoIsHls: Boolean get() = exoUrl?.endsWith(".m3u8") == true
        override val exoFailed = false
        override var mpvHasStream = false
        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) {
            assertFalse(muted); exoUrl = url; opened += url to request; order += "exo-open"
        }
        override fun exoSetMuted(muted: Boolean) {}
        override fun exoStop() { exoUrl = null; order += "exo-stop" }
        override fun exoReleaseUhdDecoder() {}
        override fun exoAbandon(reason: String) {}
        override suspend fun watchExo(channelName: String, stillOurs: () -> Boolean, handOver: suspend (String) -> Unit,
            onOpened: () -> Unit, postponeDeadline: (Long) -> Unit, log: (String) -> Unit) {
            val reason = failed.await()
            if (stillOurs()) handOver(reason)
        }
        override fun mpvPlay(url: String, request: LiveRequest) { mpvHasStream = true; fallback = url to request; order += "mpv-open" }
        override fun mpvStop() { mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() { mpvHasStream = false; order += "mpv-release" }
        override fun mpvAbandon(reason: String) {}
        override suspend fun awaitMpvOutcome(timeoutMs: Long) = MpvOutcome(true, null)
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) { reconnect = provider }
    }

    @Test fun liveStartsOnOwnTvFastEngineWithoutExtraResolutionOrMpvProbe() = runTest {
        val engines = Engines()
        val tuner = OwnTvLiveTuner(backgroundScope, engines) { testScheduler.currentTime }
        var refreshed = 0
        val headers = mapOf("User-Agent" to "FixturePlayer", "Referer" to "https://provider.test/", "Authorization" to "fixture-token")
        tuner.play("https://provider.test/live.m3u8", headers, "Channel", true) { refreshed++; "https://provider.test/fresh.m3u8" }
        runCurrent()
        assertEquals(1, engines.opened.size)
        assertNull(engines.fallback)
        assertEquals(0, refreshed)
        val request = engines.opened.single().second
        assertEquals("hls", request.manifestType)
        assertEquals(0, request.prerollSecs)
        assertEquals(headers, StreamHeaders.decode(request.httpHeaders))
        assertEquals("https://provider.test/fresh.m3u8", engines.reconnect!!.freshUrl())
        assertEquals(1, refreshed)
        tuner.stop()
    }

    @Test fun incompatibleLiveStreamFallsBackThroughUpstreamControllerWithSameHeaders() = runTest {
        val engines = Engines()
        val selected = mutableListOf<Boolean>()
        val tuner = OwnTvLiveTuner(backgroundScope, engines, onEngineStarted = { selected += it }) { testScheduler.currentTime }
        val headers = mapOf("User-Agent" to "FixturePlayer")
        tuner.play("https://provider.test/live.ts", headers, "Channel", false, null)
        runCurrent()
        assertEquals(listOf(true), selected)
        engines.failed.complete("unsupported decoder")
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertNotNull(engines.fallback)
        assertEquals(listOf(true, false), selected)
        assertEquals("https://provider.test/live.ts", engines.fallback!!.first)
        assertEquals(headers, StreamHeaders.decode(engines.fallback!!.second.httpHeaders))
        assertTrue(engines.order.indexOf("exo-stop") < engines.order.indexOf("mpv-open"))
        tuner.stop()
    }

    @Test fun rapidNewTuneCancelsEarlierQueuedRequestAndUsesOnlyItsReconnect() = runTest {
        val engines = Engines()
        val tuner = OwnTvLiveTuner(backgroundScope, engines) { testScheduler.currentTime }
        tuner.play("https://provider.test/old.ts", emptyMap(), "Old", false) { "https://provider.test/old-fresh.ts" }
        tuner.play("https://provider.test/new.ts", emptyMap(), "New", false) { "https://provider.test/new-fresh.ts" }
        runCurrent()
        assertEquals(listOf("https://provider.test/new.ts"), engines.opened.map { it.first })
        assertEquals("https://provider.test/new-fresh.ts", engines.reconnect!!.freshUrl())
        val previousReconnect = engines.reconnect!!
        tuner.play("https://provider.test/latest.ts", emptyMap(), "Latest", false) { "https://provider.test/latest-fresh.ts" }
        runCurrent()
        assertNull(previousReconnect.freshUrl())
        assertEquals("https://provider.test/latest-fresh.ts", engines.reconnect!!.freshUrl())
        tuner.stop()
    }

    @Test fun archiveHandoverReleasesExoBeforeMpvAndNewLiveCancelsPendingArchive() = runTest {
        val engines = Engines()
        val tuner = OwnTvLiveTuner(backgroundScope, engines) { testScheduler.currentTime }
        tuner.play("https://provider.test/live.ts", emptyMap(), "Live", false, null)
        runCurrent()
        var archives = 0
        tuner.playArchive { archives++; engines.order += "archive-open" }
        runCurrent()
        assertEquals(0, archives)
        advanceTimeBy(OwnTVPlayer.DECODER_RELEASE_MS); runCurrent()
        assertEquals(1, archives)
        assertTrue(engines.order.indexOf("exo-stop") < engines.order.indexOf("archive-open"))
        tuner.play("https://provider.test/live2.ts", emptyMap(), "Live2", false, null)
        runCurrent()
        tuner.playArchive { archives++ }; runCurrent()
        tuner.play("https://provider.test/live3.ts", emptyMap(), "Live3", false, null)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, archives)
        assertEquals("https://provider.test/live3.ts", engines.exoUrl)
        tuner.stop()
    }
}
