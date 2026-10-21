package com.arflix.tv.ui.screens.tv.live

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.player.LiveEngines
import tv.own.owntv.player.LiveTuneController
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerFailureReason

/** Adapt already-authorized ARVIO URLs to OwnTV's production live tune controller.
 * These entities are in-memory requests, never another catalog/account database. */
internal class OwnTvLiveTuner(
    scope: CoroutineScope,
    engines: LiveEngines,
    private val onEngineStarted: (Boolean) -> Unit = {},
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var sequence = 0L
    private var source: SourceEntity? = null
    private var refresh: (suspend () -> String?)? = null
    val controller: LiveTuneController = LiveTuneController(scope, engines, object : LiveTuneController.Host {
        override suspend fun sourceOf(sourceId: Long) = source?.takeIf { it.id == sourceId }
        override fun needsResolve(source: SourceEntity?) = source != null
        override suspend fun resolve(source: SourceEntity, cmd: String): String? =
            if (source.id == this@OwnTvLiveTuner.source?.id) refresh?.invoke() ?: cmd else null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) = Unit
        override suspend fun globalPreference() = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs() = 30
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {
            android.util.Log.w("ARVIO-Live", "OwnTV live recovery: $reason")
        }
        override fun nowMs() = this@OwnTvLiveTuner.nowMs()
        override fun onEngineStarted() = this@OwnTvLiveTuner.onEngineStarted(controller.liveOnExo.value)
    })

    fun play(url: String, headers: Map<String, String>, name: String,
        isHls: Boolean, refreshUrl: (suspend () -> String?)?) {
        val id = ++sequence
        val requestSource = SourceEntity(id = id, name = "ARVIO live", type = SourceType.M3U, url = "",
            userAgent = StreamHeaders.userAgentOf(headers), livePrerollSecs = 0, maxConnections = 1)
        val channel = ChannelEntity(id = id, sourceId = id, name = name, streamUrl = url,
            httpHeaders = StreamHeaders.encode(headers), manifestType = if (isHls) "hls" else null)
        source = requestSource
        refresh = refreshUrl
        // The caller already resolved Stalker/catch-up authorization. Reuse that
        // first URL; only recovery mints another, via the request-scoped callback.
        controller.launch { start(channel, requestSource, resolved = url) }
    }

    fun playArchive(play: () -> Unit) {
        source = null
        refresh = null
        controller.launch {
            val wasExo = liveOnExo.value
            releaseForArchive()
            // Preserve the upstream hardware decoder-release budget on handover.
            if (wasExo) delay(OwnTVPlayer.DECODER_RELEASE_MS)
            play()
        }
    }

    fun stop() { source = null; refresh = null; controller.stop() }
}
