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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
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
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.EnginePair
import tv.own.owntv.player.livePreviewEngine
import org.koin.core.KoinApplication

/**
 * Process-local OwnTV dependency graph. It is intentionally NOT installed in the
 * global Koin context and does not replace ARVIO's Hilt application graph.
 *
 * Settings/database instances are process-long so revisiting TV never creates
 * a second DataStore for the same file. Only requested bindings are instantiated;
 * OwnTV catalog, updater, accounts and background sync are not started here.
 */
internal object OwnTvIptvRuntime {
    @Volatile private var instance: KoinApplication? = null

    private fun graph(context: Context): KoinApplication = instance ?: synchronized(this) {
        instance ?: koinApplication {
            androidContext(context.applicationContext)
            modules(coreModule, databaseModule, dataModule, module {
                single { PlayerDiagnostics() }
                single { ownTVPlayer() }
                factory { livePreviewEngine() }
            })
        }.also { instance = it }
    }
    fun player(context: Context): OwnTVPlayer = graph(context).koin.get()
    fun livePlayer(context: Context): LivePreviewEngine = graph(context).koin.get()
}

/**
 * Surface/lifecycle adapter for OwnTV's fast live engine and mpv/archive engine.
 * LiveTuneController owns routing, watch/retry ladders and engine handovers.
 * The old adapter name is retained for callers; it no longer means mpv-only live.
 */
internal class OwnTvMpvFallback(context: Context) {
    private val engine = OwnTvIptvRuntime.player(context)
    private val liveEngine = OwnTvIptvRuntime.livePlayer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tuner = OwnTvLiveTuner(scope, EnginePair(liveEngine, engine))
    private val liveMode = MutableStateFlow(false)
    private val onExo = combine(liveMode, tuner.controller.liveOnExo) { live, exo -> live && exo }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val playing = combine(onExo, engine.isPlaying, liveEngine.isPlaying) { exo, mpv, fast -> if (exo) fast else mpv }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val buffering = combine(onExo, engine.buffering, liveEngine.buffering) { exo, mpv, fast -> if (exo) fast else mpv }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val positionMs: Long get() = if (onExo.value) liveEngine.livePositionMs() else engine.position.value
    val durationMs: Long get() = if (onExo.value) 0 else engine.duration.value
    val error = combine(onExo, engine.error, liveEngine.error) { exo, mpv, fast -> (if (exo) fast else mpv)?.toString() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val surfaceResetToken = combine(engine.surfaceResetToken, liveEngine.surfaceGeneration) { mpv, exo -> mpv to exo }
        .stateIn(scope, SharingStarted.Eagerly, engine.surfaceResetToken.value to liveEngine.surfaceGeneration.value)
    private var attachedHolder: SurfaceHolder? = null
    private var attachedView: PlayerView? = null
    init {
        // Both OwnTV engines own the Surface directly, not PlayerView.player.
        // Pass their aspect changes to the content frame, including fullscreen.
        combine(onExo, engine.videoAspect, liveEngine.videoAspect) { exo, mpv, fast -> if (exo) fast else mpv }.onEach { aspect ->
            attachedView?.let { fitOwnTvVideo(it, aspect) }
        }.launchIn(scope)
        onExo.onEach { attachedHolder?.let(::attachSurface) }.launchIn(scope)
    }
    private fun attachSurface(holder: SurfaceHolder) {
        if (!holder.surface.isValid) return
        if (onExo.value) {
            engine.detachSurface(holder.surface)
            liveEngine.setSurface(holder.surface)
        } else {
            liveEngine.detachSurface(holder.surface)
            engine.attachSurface(holder.surface)
            engine.setSurfaceSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        }
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            attachSurface(holder)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!onExo.value) engine.setSurfaceSize(width, height)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // OwnTV's identity-aware detach cannot detach a newer fullscreen surface.
            engine.detachSurface(holder.surface)
            liveEngine.detachSurface(holder.surface)
        }
    }

    val available: Boolean get() = true

    fun attach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) return
        attachedHolder?.removeCallback(surfaceCallback)
        attachedView = playerView
        fitOwnTvVideo(playerView, if (onExo.value) liveEngine.videoAspect.value else engine.videoAspect.value)
        attachedHolder = holder
        holder.addCallback(surfaceCallback)
        if (holder.surface.isValid) {
            attachSurface(holder)
        }
    }

    fun detach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) {
            holder.removeCallback(surfaceCallback)
            engine.detachSurface(holder.surface)
            liveEngine.detachSurface(holder.surface)
            attachedHolder = null
            attachedView = null
        }
    }

    fun play(url: String, headers: Map<String, String>, archive: Boolean = false, startPositionMs: Long = 0,
        refreshUrl: (suspend () -> String?)? = null, isHls: Boolean = false, name: String = "Live channel") {
        liveMode.value = !archive
        if (!archive) {
            tuner.play(url, headers, name, isHls, refreshUrl)
            return
        }
        tuner.playArchive { engine.play(url = url, isLive = false, isArchive = true,
            startPositionMs = startPositionMs, muted = false,
            userAgent = StreamHeaders.userAgentOf(headers), httpHeaders = StreamHeaders.encode(headers),
            // Always replace the provider: upstream deliberately retains a live
            // provider when passed null, which would reuse the previous channel.
            reconnectProvider = ReconnectUrlProvider { refreshUrl?.invoke() }) }
    }

    fun stop() { tuner.stop(); liveMode.value = false }
    fun togglePlayPause() { if (onExo.value) liveEngine.togglePlayPause() else engine.togglePlayPause() }
    fun setPlaying(play: Boolean) { if (playing.value != play) togglePlayPause() }
    fun seekTo(position: Long) = engine.seekBy(position - engine.position.value)
    fun pause() { if (onExo.value) liveEngine.onAppBackgrounded() else engine.onAppBackgrounded() }
    fun resume() { if (onExo.value) liveEngine.onAppForegrounded() else engine.onAppForegrounded() }

    fun release() {
        attachedHolder?.let {
            it.removeCallback(surfaceCallback)
            engine.detachSurface(it.surface)
            liveEngine.detachSurface(it.surface)
        }
        attachedHolder = null
        attachedView = null
        tuner.stop()
        liveEngine.release()
        liveEngine.discardBackgroundRestore()
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
