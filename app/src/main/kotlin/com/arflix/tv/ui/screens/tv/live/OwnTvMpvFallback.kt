package com.arflix.tv.ui.screens.tv.live

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import dev.jdtech.mpv.MPVLib
import tv.own.owntv.core.network.StreamHeaders

/**
 * Small ARVIO host for OwnTV's libmpv engine.
 *
 * OwnTV's full player is deliberately coupled to its own database and settings graph.  This host
 * keeps that graph out of ARVIO while retaining the important recovery path: when Media3 cannot
 * open a live stream, hand the same source and per-channel HTTP headers to FFmpeg/mpv on the
 * already-visible PlayerView surface.
 */
internal class OwnTvMpvFallback(context: Context) {
    private val appContext = context.applicationContext
    private var mpv: MPVLib? = null
    private var attachedHolder: SurfaceHolder? = null
    private var pendingUrl: String? = null
    private var pendingHeaders: Map<String, String> = emptyMap()

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder.surface)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (attachedHolder === holder) {
                runCatching { mpv?.detachSurface() }
            }
        }
    }

    val available: Boolean get() = ensurePlayer() != null

    fun attach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) {
            if (holder.surface.isValid) attachSurface(holder.surface)
            return
        }
        attachedHolder?.removeCallback(surfaceCallback)
        attachedHolder = holder
        holder.addCallback(surfaceCallback)
        if (holder.surface.isValid) attachSurface(holder.surface)
    }

    fun detach(playerView: PlayerView?) {
        val holder = (playerView?.videoSurfaceView as? SurfaceView)?.holder ?: return
        if (attachedHolder === holder) {
            holder.removeCallback(surfaceCallback)
            attachedHolder = null
            runCatching { mpv?.detachSurface() }
        }
    }

    fun play(url: String, headers: Map<String, String>) {
        pendingUrl = url
        pendingHeaders = headers
        val player = ensurePlayer() ?: return
        applyAndLoad(player)
    }

    fun stop() {
        pendingUrl = null
        runCatching { mpv?.command(arrayOf("stop")) }
    }

    fun release() {
        pendingUrl = null
        attachedHolder?.removeCallback(surfaceCallback)
        attachedHolder = null
        mpv?.let { player ->
            runCatching { player.detachSurface() }
            runCatching { player.destroy() }
        }
        mpv = null
    }

    private fun ensurePlayer(): MPVLib? {
        mpv?.let { return it }
        return runCatching {
            MPVLib.create(appContext)?.apply {
                setOptionString("vo", "mediacodec_embed")
                setOptionString("hwdec", "mediacodec")
                setOptionString("ao", "audiotrack")
                setOptionString("force-window", "no")
                setOptionString("idle", "yes")
                setOptionString("ytdl", "no")
                setOptionString("tls-verify", "no")
                setOptionString("curl-enabled", "no")
                setOptionString("cache", "yes")
                setOptionString("demuxer-max-bytes", "100MiB")
                setOptionString("demuxer-max-back-bytes", "12MiB")
                setOptionString("demuxer-readahead-secs", "12")
                setOptionString("network-timeout", "30")
                setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4")
                init()
            }
        }.getOrNull()?.also { created ->
            mpv = created
            attachedHolder?.surface?.takeIf { it.isValid }?.let(::attachSurface)
        }
    }

    private fun attachSurface(surface: Surface) {
        val player = ensurePlayer() ?: return
        runCatching {
            player.attachSurface(surface)
            applyAndLoad(player)
        }
    }

    private fun applyAndLoad(player: MPVLib) {
        val url = pendingUrl ?: return
        runCatching {
            val userAgent = StreamHeaders.userAgentOf(pendingHeaders)
            player.setPropertyString("user-agent", userAgent ?: "VLC/3.0.20 LibVLC/3.0.20")
            player.setPropertyString("http-header-fields", StreamHeaders.toMpvHeaderFields(pendingHeaders))
            player.command(arrayOf("loadfile", url, "replace"))
            player.setPropertyBoolean("pause", false)
        }
    }
}
