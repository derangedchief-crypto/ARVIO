package com.arflix.tv.ui.screens.tv.live

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import tv.own.owntv.core.di.coreModule
import tv.own.owntv.core.di.databaseModule
import tv.own.owntv.core.di.dataModule
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerDiagnostics
import tv.own.owntv.player.ownTVPlayer

/**
 * Process-local OwnTV dependency graph. It is intentionally NOT installed in the
 * global Koin context and does not replace ARVIO's Hilt application graph.
 *
 * Settings/database instances are process-long so revisiting TV never creates
 * a second DataStore for the same file. Only requested bindings are instantiated;
 * OwnTV catalog, updater, accounts and background sync are not started here.
 */
internal object OwnTvIptvRuntime {
    @Volatile private var instance: OwnTVPlayer? = null

    fun player(context: Context): OwnTVPlayer = instance ?: synchronized(this) {
        instance ?: koinApplication {
            androidContext(context.applicationContext)
            modules(coreModule, databaseModule, dataModule, module {
                single { PlayerDiagnostics() }
                single { ownTVPlayer() }
            })
        }.koin.get<OwnTVPlayer>().also { instance = it }
    }
}

/**
 * Surface/lifecycle adapter for the actual OwnTV Player Core, not a reimplemented
 * libmpv wrapper. Upstream owns decoding, asynchronous mpv commands, retry ladders,
 * buffering budgets, diagnostics and network recovery. ARVIO owns route lifetime.
 */
internal class OwnTvMpvFallback(context: Context) {
    private val engine = OwnTvIptvRuntime.player(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playing = engine.isPlaying
    val buffering = engine.buffering
    val positionMs: Long get() = engine.position.value
    val durationMs: Long get() = engine.duration.value
    val error = engine.error.map { it?.toString() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    private var attachedHolder: SurfaceHolder? = null
    private var attachedView: PlayerView? = null
    init {
        // PlayerView has no Media3 Player while mpv owns its surface, so it will
        // never receive Media3's automatic video-size callback. Pass upstream
        // aspect changes to its content frame ourselves, including fullscreen.
        engine.videoAspect.onEach { aspect ->
            attachedView?.let { fitOwnTvVideo(it, aspect) }
        }.launchIn(scope)
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            engine.attachSurface(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            engine.setSurfaceSize(width, height)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // OwnTV's identity-aware detach cannot detach a newer fullscreen surface.
            engine.detachSurface(holder.surface)
        }
    }

    val available: Boolean get() = true

    fun attach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) return
        attachedHolder?.removeCallback(surfaceCallback)
        attachedView = playerView
        fitOwnTvVideo(playerView, engine.videoAspect.value)
        attachedHolder = holder
        holder.addCallback(surfaceCallback)
        if (holder.surface.isValid) {
            engine.attachSurface(holder.surface)
            engine.setSurfaceSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        }
    }

    fun detach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) {
            holder.removeCallback(surfaceCallback)
            engine.detachSurface(holder.surface)
            attachedHolder = null
            attachedView = null
        }
    }

    fun play(url: String, headers: Map<String, String>, archive: Boolean = false, startPositionMs: Long = 0,
        refreshUrl: (suspend () -> String?)? = null) {
        engine.play(url = url, isLive = !archive, isArchive = archive,
            startPositionMs = startPositionMs, muted = false,
            // Do not inherit an invisible OwnTV global pre-roll gate for ARVIO
            // live tunes. Preserve upstream probing, cache depth and retry logic.
            livePrerollSecsOverride = if (archive) null else 0,
            userAgent = StreamHeaders.userAgentOf(headers), httpHeaders = StreamHeaders.encode(headers),
            // Always replace the provider: upstream deliberately retains a live
            // provider when passed null, which would reuse the previous channel.
            reconnectProvider = ReconnectUrlProvider { refreshUrl?.invoke() })
    }

    fun stop() = engine.stop()
    fun togglePlayPause() = engine.togglePlayPause()
    fun setPlaying(play: Boolean) { if (engine.isPlaying.value != play) engine.togglePlayPause() }
    fun seekTo(position: Long) = engine.seekBy(position - engine.position.value)
    fun pause() = engine.onAppBackgrounded()
    fun resume() = engine.onAppForegrounded()

    fun release() {
        attachedHolder?.let {
            it.removeCallback(surfaceCallback)
            engine.detachSurface(it.surface)
        }
        attachedHolder = null
        attachedView = null
        engine.stop()
        engine.discardBackgroundRestore()
        scope.cancel()
    }
}

/** Preserve the source's display aspect rather than stretching it to the host SurfaceView. */
internal fun fitOwnTvVideo(view: PlayerView, videoAspect: Float?) {
    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?.setAspectRatio(videoAspect?.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f))
}
