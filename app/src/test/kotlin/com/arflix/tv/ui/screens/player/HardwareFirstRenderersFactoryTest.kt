package com.arflix.tv.ui.screens.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=com.arflix.tv.SentryRegressionTestApplication::class,manifest=Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HardwareFirstRenderersFactoryTest {
    private class Exposed:HardwareFirstRenderersFactory(RuntimeEnvironment.getApplication()){
        fun audio():List<Renderer>{
            val out=arrayListOf<Renderer>();val context=RuntimeEnvironment.getApplication()
            buildAudioRenderers(context,EXTENSION_RENDERER_MODE_ON,MediaCodecSelector.DEFAULT,true,
                DefaultAudioSink.Builder(context).build(),Handler(Looper.getMainLooper()),mockk<AudioRendererEventListener>(relaxed=true),out)
            return out
        }
        fun video():List<Renderer>{
            val out=arrayListOf<Renderer>()
            buildVideoRenderers(RuntimeEnvironment.getApplication(),EXTENSION_RENDERER_MODE_ON,MediaCodecSelector.DEFAULT,
                true,Handler(Looper.getMainLooper()),mockk<VideoRendererEventListener>(relaxed=true),5000,out)
            return out
        }
    }
    @Test fun audioKeepsHardwarePassthroughFirstAndBundledFfmpegFallback(){
        val names=Exposed().audio().map{it.name}
        assertEquals(listOf("MediaCodecAudioRenderer","FfmpegAudioRenderer"),names)
    }
    @Test fun videoKeepsHardwareFirstAndBundledSoftwareFallback(){
        val names=Exposed().video().map{it.name}
        assertEquals(listOf("MediaCodecVideoRenderer","ExperimentalFfmpegVideoRenderer"),names)
    }
}
