package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvNowNext
import kotlinx.coroutines.delay

/** New TV-only fullscreen surface chrome; all playback/catch-up commands remain upstream callbacks. */
@Composable
fun OwnTvPlayerHud(
    channel: EnrichedChannel?, nowNext: IptvNowNext?, pokeSignal: Int, categoryName: String? = null,
    isCatchupMode: Boolean = false, isPlaying: Boolean = true, isBuffering: Boolean = false,
    playbackPositionMs: Long = 0, playbackDurationMs: Long = 0,
    onBackClick: (() -> Unit)? = null, onGuideClick: (() -> Unit)? = null,
    onPlayPauseClick: (() -> Unit)? = null, onRewindClick: (() -> Unit)? = null,
    onFastForwardClick: (() -> Unit)? = null, onPreviousCatchupClick: (() -> Unit)? = null,
    onNextCatchupClick: (() -> Unit)? = null, onReplayClick: (() -> Unit)? = null,
    onGoLiveClick: (() -> Unit)? = null, onSeekToPosition: ((Long) -> Unit)? = null,
    onOpenQuickZap: (() -> Unit)? = null, onVisibilityChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val visibilityCallback by rememberUpdatedState(onVisibilityChanged)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(pokeSignal, dragging) {
        visible = true
        visibilityCallback?.invoke(true)
        if (!dragging) { delay(5_000); visible = false; visibilityCallback?.invoke(false) }
    }
    LaunchedEffect(visible) {
        if (visible) { delay(100); runCatching { playFocus.requestFocus() } }
    }
    DisposableEffect(Unit) { onDispose { visibilityCallback?.invoke(false) } }
    Box(modifier.fillMaxSize()) {
        if (isBuffering) CircularProgressIndicator(color = TvStageStyle.Accent,
            modifier = Modifier.align(Alignment.Center).testTag("live-playback-buffering"))
        if (visible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent,
                0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.95f))))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBackClick != null) TvStageAction(stringResource(R.string.tv_stage_back), onClick = onBackClick)
                Text(categoryName.orEmpty(), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (isCatchupMode) stringResource(R.string.tv_stage_archive) else stringResource(R.string.tv_stage_live),
                    color = TvStageStyle.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp)
                .background(TvStageStyle.Surface.copy(alpha = 0.94f), RoundedCornerShape(20.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(channel?.let { "${it.number} · ${it.name}" } ?: stringResource(R.string.tv_stage_title),
                    color = TvStageStyle.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nowNext?.now?.title ?: channel?.name.orEmpty(), color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val current = nowNext?.now
                val duration = if (isCatchupMode) playbackDurationMs else current?.let { it.endUtcMillis - it.startUtcMillis } ?: 0L
                val position = if (isCatchupMode) playbackPositionMs else current?.let { System.currentTimeMillis() - it.startUtcMillis } ?: 0L
                if (duration > 0 && onSeekToPosition != null) Slider(
                    value = if (dragging) dragPosition else (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = true; dragPosition = it },
                    onValueChangeFinished = { onSeekToPosition((dragPosition * duration).toLong()); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = TvStageStyle.Accent, activeTrackColor = TvStageStyle.Accent))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isCatchupMode && onPreviousCatchupClick != null)
                        TvStageAction(stringResource(R.string.tv_stage_previous), onClick = onPreviousCatchupClick)
                    if (onRewindClick != null) TvStageAction("−10s", onClick = onRewindClick)
                    if (onPlayPauseClick != null) TvStageAction(
                        stringResource(if (isPlaying) R.string.tv_stage_pause else R.string.tv_stage_play),
                        modifier = Modifier.focusRequester(playFocus), onClick = onPlayPauseClick)
                    if (onFastForwardClick != null) TvStageAction("+10s", onClick = onFastForwardClick)
                    if (isCatchupMode && onNextCatchupClick != null)
                        TvStageAction(stringResource(R.string.tv_stage_next_programme), onClick = onNextCatchupClick)
                    if (onGuideClick != null) TvStageAction(stringResource(R.string.tv_stage_guide), onClick = onGuideClick)
                    if (onOpenQuickZap != null) TvStageAction(stringResource(R.string.tv_stage_channels), onClick = onOpenQuickZap)
                    if (onReplayClick != null) TvStageAction(stringResource(R.string.tv_stage_replay), onClick = onReplayClick)
                    if (isCatchupMode && onGoLiveClick != null)
                        TvStageAction(stringResource(R.string.tv_stage_go_live), selected = true, onClick = onGoLiveClick)
                }
            }
        }
    }
}
