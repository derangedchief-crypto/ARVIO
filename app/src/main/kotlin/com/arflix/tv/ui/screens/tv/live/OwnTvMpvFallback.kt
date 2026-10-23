package com.arflix.tv.ui.screens.tv.live

import android.content.Context
import android.view.SurfaceHolder
import android.view.Surface
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
import com.arflix.tv.util.AppLogger

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
internal class OwnTvMpvFallback(context: Context, prebufferSeconds: suspend () -> Int = { 0 }) {
    private val engine = OwnTvIptvRuntime.player(context)
    private val liveEngine = OwnTvIptvRuntime.livePlayer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val surfaceEngine = MutableStateFlow(OwnTvSurfaceEngine.NONE)
    private val tuner = OwnTvLiveTuner(scope, EnginePair(liveEngine, engine), prebufferSeconds = prebufferSeconds, onEngineStarted = { fast ->
        selectSurfaceEngine(if (fast) OwnTvSurfaceEngine.FAST_LIVE else OwnTvSurfaceEngine.MPV)
    })
    val playing = combine(surfaceEngine, engine.isPlaying, liveEngine.isPlaying) { owner, mpv, fast ->
        when (owner) { OwnTvSurfaceEngine.FAST_LIVE -> fast; OwnTvSurfaceEngine.MPV -> mpv; else -> false }
    }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val buffering = combine(surfaceEngine, engine.buffering, liveEngine.buffering) { owner, mpv, fast ->
        when (owner) { OwnTvSurfaceEngine.FAST_LIVE -> fast; OwnTvSurfaceEngine.MPV -> mpv; else -> false }
    }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val positionMs: Long get() = when (surfaceEngine.value) {
        OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.livePositionMs()
        OwnTvSurfaceEngine.MPV -> engine.position.value
        else -> 0L
    }
    val durationMs: Long get() = if (surfaceEngine.value == OwnTvSurfaceEngine.MPV) engine.duration.value else 0L
    val error = combine(surfaceEngine, engine.error, liveEngine.error) { owner, mpv, fast ->
        when (owner) { OwnTvSurfaceEngine.FAST_LIVE -> fast; OwnTvSurfaceEngine.MPV -> mpv; else -> null }?.toString()
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val surfaceResetToken = combine(surfaceEngine, engine.surfaceResetToken, liveEngine.surfaceGeneration) { owner, mpv, fast ->
        when (owner) { OwnTvSurfaceEngine.MPV -> mpv to 0; OwnTvSurfaceEngine.FAST_LIVE -> 0 to fast; else -> 0 to 0 }
    }.stateIn(scope, SharingStarted.Eagerly, 0 to 0)
    private var attachedHolder: SurfaceHolder? = null
    private var attachedView: PlayerView? = null
    private val observedHolders = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<SurfaceHolder, Boolean>())
    private val surfaces = OwnTvSurfaceHandoff<SurfaceHolder, Surface>(
        valid = { it.isValid },
        attach = { owner, holder, surface ->
            val frame = holder.surfaceFrame
            AppLogger.breadcrumb("LiveSurface", "attach owner=$owner viewSize=${frame.width()}x${frame.height()}")
            when (owner) {
                OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.setSurface(surface)
                OwnTvSurfaceEngine.MPV -> {
                    engine.attachSurface(surface)
                    if (frame.width() > 0 && frame.height() > 0) engine.setSurfaceSize(frame.width(), frame.height())
                }
                else -> Unit
            }
        },
        detach = { owner, surface ->
            AppLogger.breadcrumb("LiveSurface", "detach owner=$owner")
            when (owner) {
                OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.detachSurface(surface)
                OwnTvSurfaceEngine.MPV -> engine.detachSurface(surface)
                else -> Unit
            }
        },
        resize = { owner, width, height -> if (owner == OwnTvSurfaceEngine.MPV) engine.setSurfaceSize(width, height) },
    )
    init {
        // Both OwnTV engines own the Surface directly, not PlayerView.player.
        // Pass their aspect changes to the content frame, including fullscreen.
        combine(surfaceEngine, engine.videoAspect, liveEngine.videoAspect) { owner, mpv, fast ->
            when (owner) { OwnTvSurfaceEngine.FAST_LIVE -> fast; OwnTvSurfaceEngine.MPV -> mpv; else -> null }
        }.onEach { aspect ->
            attachedView?.let { fitOwnTvVideo(it, aspect) }
        }.launchIn(scope)
    }
    private fun selectSurfaceEngine(owner: OwnTvSurfaceEngine) {
        surfaces.selectEngine(owner)
        if (surfaceEngine.value != owner) {
            AppLogger.setCrashContext("live_surface_owner", owner.name)
            AppLogger.breadcrumb("LiveSurface", "engine=$owner")
        }
        surfaceEngine.value = owner
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaces.created(holder, holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaces.changed(holder, width, height)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaces.destroyed(holder)
        }
    }

    val available: Boolean get() = true

    fun attach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) return
        // Keep the outgoing observer until that view is released. If the new
        // surface is not ready yet, the old one is still bound and its destroy
        // callback must detach the decoder BEFORE the framework frees it.
        attachedView = playerView
        fitOwnTvVideo(playerView, when (surfaceEngine.value) {
            OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.videoAspect.value
            OwnTvSurfaceEngine.MPV -> engine.videoAspect.value
            else -> null
        })
        attachedHolder = holder
        if (observedHolders.add(holder)) holder.addCallback(surfaceCallback)
        surfaces.request(holder, holder.surface)
    }

    fun detach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        surfaces.remove(holder)
        holder.removeCallback(surfaceCallback)
        observedHolders.remove(holder)
        if (attachedHolder === holder) {
            attachedHolder = null
            attachedView = null
        }
    }

    fun play(url: String, headers: Map<String, String>, archive: Boolean = false, startPositionMs: Long = 0,
        refreshUrl: (suspend () -> String?)? = null, isHls: Boolean = false, name: String = "Live channel") {
        engine.discardBackgroundRestore()
        liveEngine.discardBackgroundRestore()
        if (!archive) {
            // Select synchronously, before any view callback or coroutine can
            // temporarily attach the inactive mpv core during a fast live tune.
            selectSurfaceEngine(OwnTvSurfaceEngine.FAST_LIVE)
            tuner.play(url, headers, name, isHls, refreshUrl)
            return
        }
        tuner.playArchive {
            selectSurfaceEngine(OwnTvSurfaceEngine.MPV)
            engine.play(url = url, isLive = false, isArchive = true,
            startPositionMs = startPositionMs, muted = false,
            userAgent = StreamHeaders.userAgentOf(headers), httpHeaders = StreamHeaders.encode(headers),
            // Always replace the provider: upstream deliberately retains a live
            // provider when passed null, which would reuse the previous channel.
            reconnectProvider = ReconnectUrlProvider { refreshUrl?.invoke() }) }
    }

    fun stop() {
        tuner.stop(); selectSurfaceEngine(OwnTvSurfaceEngine.NONE)
        engine.discardBackgroundRestore(); liveEngine.discardBackgroundRestore()
    }
    fun togglePlayPause() {
        when (surfaceEngine.value) {
            OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.togglePlayPause()
            OwnTvSurfaceEngine.MPV -> engine.togglePlayPause()
            else -> Unit
        }
    }
    fun setPlaying(play: Boolean) {
        val actual = when (surfaceEngine.value) {
            OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.isPlaying.value
            OwnTvSurfaceEngine.MPV -> engine.isPlaying.value
            else -> false
        }
        if (actual != play) togglePlayPause()
    }
    fun seekTo(position: Long) { if (surfaceEngine.value == OwnTvSurfaceEngine.MPV) engine.seekBy(position - engine.position.value) }
    fun pause() {
        when (surfaceEngine.value) {
            OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.onAppBackgrounded()
            OwnTvSurfaceEngine.MPV -> engine.onAppBackgrounded()
            else -> Unit
        }
    }
    fun resume() {
        when (surfaceEngine.value) {
            OwnTvSurfaceEngine.FAST_LIVE -> liveEngine.onAppForegrounded()
            OwnTvSurfaceEngine.MPV -> engine.onAppForegrounded()
            else -> Unit
        }
    }

    fun release() {
        surfaces.clear()
        observedHolders.forEach { it.removeCallback(surfaceCallback) }
        observedHolders.clear()
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
