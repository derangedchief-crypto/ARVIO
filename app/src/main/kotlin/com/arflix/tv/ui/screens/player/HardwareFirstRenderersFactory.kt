package com.arflix.tv.ui.screens.player

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Avoid repeated ClassNotFoundException probes for optional MIDI/Opus/FLAC/VP9/
 * AV1 extensions we do not ship. Keep platform audio/passthrough and video FIRST,
 * with the actual bundled FFmpeg extensions as fallbacks. Media3 stays aligned.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class HardwareFirstRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            BundledRendererConstructors.audio?.let { out.add(it.newInstance(eventHandler,eventListener,audioSink) as Renderer) }
        }
    }

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector,
            enableDecoderFallback,eventHandler,eventListener,allowedVideoJoiningTimeMs,out)
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            BundledRendererConstructors.video?.let { out.add(it.newInstance(allowedVideoJoiningTimeMs,eventHandler,eventListener,50) as Renderer) }
        }
    }
}

private object BundledRendererConstructors {
    // Lazy once per process, including the absent-extension result on Play builds.
    val audio by lazy {
        try { Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            .getConstructor(Handler::class.java,AudioRendererEventListener::class.java,AudioSink::class.java) }
        catch (_: ClassNotFoundException) { null }
    }
    val video by lazy {
        try { Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer")
            .getConstructor(Long::class.javaPrimitiveType,Handler::class.java,VideoRendererEventListener::class.java,Int::class.javaPrimitiveType) }
        catch (_: ClassNotFoundException) { null }
    }
}
