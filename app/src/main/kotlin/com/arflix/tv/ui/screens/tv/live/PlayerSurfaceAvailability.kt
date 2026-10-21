package com.arflix.tv.ui.screens.tv.live

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.media3.ui.PlayerView

internal class PlayerSurfaceAvailabilityObserver(private val changed: () -> Unit) : SurfaceHolder.Callback {
    private val holders = linkedSetOf<SurfaceHolder>()
    private var closed = false
    fun observe(holder: SurfaceHolder) { if (!closed && holders.add(holder)) holder.addCallback(this) }
    fun close() {
        closed = true
        holders.forEach { it.removeCallback(this) }
        holders.clear()
    }
    override fun surfaceCreated(holder: SurfaceHolder) { if (!closed) changed() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { if (!closed) changed() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (!closed) changed() }
}

/** Surface creation does not change the PlayerView reference. Wake the binding
 * effect when a target becomes ready instead of clearing a working output early. */
@Composable
internal fun rememberPlayerSurfaceRevision(view: PlayerView?): Int {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val holders = listOfNotNull((view?.videoSurfaceView as? SurfaceView)?.holder)
        val observer = PlayerSurfaceAvailabilityObserver { revision++ }
        holders.forEach(observer::observe)
        onDispose { observer.close() }
    }
    return revision
}

internal fun playerSurfaceReady(view: PlayerView): Boolean =
    (view.videoSurfaceView as? SurfaceView)?.holder?.surface?.isValid != false
