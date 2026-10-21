package com.arflix.tv.ui.screens.tv.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.arflix.tv.R
import kotlin.math.roundToInt

/** Window-space preview bounds are translated into the Live TV root, not the
 * activity, so outer navigation bars/padding do not move the video off its slot. */
internal fun liveVideoPlacement(rootSize: IntSize, rootOrigin: Offset, previewInWindow: Rect?, fullscreen: Boolean): Rect? {
    if (rootSize.width <= 0 || rootSize.height <= 0) return null
    val full = Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
    if (fullscreen) return full
    val preview = previewInWindow?.takeIf { it.width > 0f && it.height > 0f } ?: return full
    return Rect(preview.topLeft - rootOrigin, preview.size)
}

/** One AndroidView at one composition location for the lifetime of playback.
 * Presentation changes only bounds; the key changes only for explicit native
 * recovery, never for fullscreen/preview. No reparenting or output-surface swap. */
@Composable
internal fun PersistentLiveVideoLayer(
    bounds: Rect?, visible: Boolean, fullscreen: Boolean, resetKey: Any?, channel: EnrichedChannel?,
    onOpenFullscreen: () -> Unit, onCreated: (PlayerView) -> Unit, onReleased: (PlayerView) -> Unit,
) {
    val density = LocalDensity.current
    val rect = bounds?.takeIf { visible } ?: Rect(-2f, -2f, -1f, -1f)
    val width = with(density) { rect.width.coerceAtLeast(1f).toDp() }
    val height = with(density) { rect.height.coerceAtLeast(1f).toDp() }
    val shape = RoundedCornerShape(if (fullscreen) 0.dp else 18.dp)
    Box(Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .requiredSize(width, height).graphicsLayer { alpha = if (visible && bounds != null) 1f else 0f }
        .clip(shape).background(Color.Black)
        .then(if (fullscreen) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape))
        // The guide's logical preview slot owns remote focus; this overlay only
        // mirrors its touch action, avoiding a duplicate keyboard focus stop.
        .focusProperties { canFocus = false }
        .clickable(enabled = visible && !fullscreen, onClick = onOpenFullscreen)) {
        key(resetKey) {
            AndroidView(factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.live_tv_player_view, null) as PlayerView).apply {
                    useController = false; keepScreenOn = true; setKeepContentOnPlayerReset(true)
                    isFocusable = false; isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }.also(onCreated)
            }, update = { view -> view.keepScreenOn = visible; onCreated(view) },
                onRelease = onReleased, modifier = Modifier.fillMaxSize())
        }
        if (!fullscreen) OwnTvPreviewVideoDecorations(channel)
    }
}
