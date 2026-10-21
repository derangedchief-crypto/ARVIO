// SPDX-License-Identifier: GPL-3.0-or-later
// Programme-strip rendering and time-cursor navigation adapted from OwnTV
// GuideCore.kt / EpgScreen.kt, commit cead977803d90bd62ece69b4140e373f01feddca.
package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.EpgDedupe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * OwnTV's canvas-strip guide hosted in ARVIO. One focus target per channel row,
 * not per programme. The ARVIO adapter retains provider IDs and catch-up actions;
 * OwnTV programme entities, deduplication, cursor movement and clipped canvas
 * rendering drive the guide. Only visible LazyColumn rows are composed.
 */
@Composable
fun TvGuideViewport(
    channels: List<EnrichedChannel>,
    channelWindowOffset: Int = 0,
    totalChannelCount: Int = channels.size,
    clockTickMillis: Long,
    indexedPrograms: Map<String, List<IptvProgram>>,
    fallbackNowNext: Map<String, IptvNowNext>,
    channelNowNext: Map<String, IptvNowNext>,
    epgLoadingChannelIds: Set<String> = emptySet(),
    epgAttemptedChannelIds: Set<String> = emptySet(),
    isGuideBackfillLoading: Boolean = false,
    hasGuideSource: Boolean = true,
    selectedChannelId: String?,
    focusSelectedChannelSignal: Int,
    focusEpgSignal: Int = 0,
    focusMode: EpgGridFocusMode = EpgGridFocusMode.ChannelList,
    scrollResetKey: String = "",
    onChannelSelect: (EnrichedChannel) -> Unit,
    onProgramSelect: (EnrichedChannel, IptvProgram?) -> Unit = { channel, _ -> onChannelSelect(channel) },
    onChannelFocused: (EnrichedChannel) -> Unit = {},
    onChannelFavoriteToggle: (String) -> Unit,
    favorites: Set<String>,
    variantCountFor: (EnrichedChannel) -> Int = { 1 },
    onOpenVariants: (EnrichedChannel) -> Unit = {},
    compact: Boolean = false,
    gridFocused: Boolean = false,
    onMoveLeftFromChannels: () -> Unit = {},
    onEnterEpg: (EnrichedChannel) -> Unit = {},
    onExitEpg: (EnrichedChannel?) -> Unit = {},
    onRequestPreviousChannels: () -> Unit = {},
    onRequestNextChannels: () -> Unit = {},
    onVisibleChannelsChanged: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHeight = if (compact) 52.dp else LiveDims.EpgRowHeight
    val columnWidth = if (compact) 164.dp else LiveDims.EpgChannelColWidth
    val density = LocalDensity.current
    val pixelsPerMinute = with(density) { (if (compact) 3.2.dp else 5.dp).toPx() }
    val start = (clockTickMillis / 1_800_000L) * 1_800_000L - 7_200_000L
    val end = start + 8 * 3_600_000L
    var cursor by remember { mutableLongStateOf(clockTickMillis) }
    var viewStart by remember { mutableLongStateOf(clockTickMillis / 1_800_000L * 1_800_000L) }
    LaunchedEffect(start, end) {
        cursor = cursor.coerceIn(start, end - 1)
        viewStart = viewStart.coerceIn(start, end - 1_800_000L)
    }
    val channelRequests = remember { mutableMapOf<String, FocusRequester>() }
    val programmeRequests = remember { mutableMapOf<String, FocusRequester>() }
    val channelIds = remember(channels) { channels.map { it.id } }
    SideEffect {
        val retained = channelIds.toSet()
        channelRequests.keys.retainAll(retained)
        programmeRequests.keys.retainAll(retained)
    }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val emptyLabel = stringResource(R.string.program_unknown)
    val headerMeasurer = rememberTextMeasurer()
    val headerStyle = TextStyle(fontSize = 12.sp, color = Color.LightGray)

    suspend fun focusRow(index: Int, programme: Boolean) {
        val channel = channels.getOrNull(index) ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index
        val last = visible.lastOrNull()?.index
        // OwnTV's row cursor must not pin every newly focused row to the top.
        // Compose's focus bring-into-view handles rows already in the viewport.
        if (first == null || last == null || index !in first..last) {
            listState.scrollToItem(if (last != null && index > last)
                (index - (last - (first ?: last))).coerceAtLeast(0) else index)
        }
        // Wait for the LazyColumn to compose the target before requesting focus.
        withFrameNanos { }
        val requests = if (programme) programmeRequests else channelRequests
        requests[channel.id]?.let { runCatching { it.requestFocus() } }
    }
    LaunchedEffect(focusSelectedChannelSignal, focusEpgSignal, focusMode) {
        if (focusSelectedChannelSignal == 0 && focusEpgSignal == 0) return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == selectedChannelId }.coerceAtLeast(0)
        focusRow(index, focusMode == EpgGridFocusMode.Epg)
    }
    LaunchedEffect(scrollResetKey.split('|').take(2), channels.isEmpty()) {
        if (channels.isNotEmpty()) listState.scrollToItem(0)
    }
    val requestPrevious by rememberUpdatedState(onRequestPreviousChannels)
    val requestNext by rememberUpdatedState(onRequestNextChannels)
    val visibleChannelsChanged by rememberUpdatedState(onVisibleChannelsChanged)
    LaunchedEffect(listState, channelIds, channelWindowOffset, totalChannelCount) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: -1) to (visible.lastOrNull()?.index ?: -1)
        }.distinctUntilChanged().collect { (first, last) ->
            if (first >= 0) visibleChannelsChanged(first, last)
            if (first >= 0 && first < 6 && channelWindowOffset > 0) requestPrevious()
            if (last >= channels.size - 6 && last >= 0 &&
                channelWindowOffset + channels.size < totalChannelCount) requestNext()
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(32.dp)) {
            Box(Modifier.width(columnWidth))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                var tick = viewStart / 1_800_000L * 1_800_000L
                while ((tick - viewStart) / 60_000f * pixelsPerMinute < size.width) {
                    val x = (tick - viewStart) / 60_000f * pixelsPerMinute
                    if (x >= 0) drawText(headerMeasurer.measure(formatter.format(Instant.ofEpochMilli(tick)), headerStyle),
                        topLeft = Offset(x + 6, 4f))
                    tick += 1_800_000L
                }
                val nowX = (clockTickMillis - viewStart) / 60_000f * pixelsPerMinute
                if (nowX in 0f..size.width) drawLine(TvStageStyle.Accent, Offset(nowX, 0f), Offset(nowX, size.height), 2f)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                val channelFocus = channelRequests.getOrPut(channel.id) { FocusRequester() }
                val programmeFocus = programmeRequests.getOrPut(channel.id) { FocusRequester() }
                val programs = remember(channel.id, indexedPrograms[channel.id], fallbackNowNext[channel.id]) {
                    val fallback = fallbackNowNext[channel.id]
                    val raw = indexedPrograms[channel.id].orEmpty().ifEmpty {
                        listOfNotNull(fallback?.now, fallback?.next, fallback?.later) +
                            fallback?.recent.orEmpty() + fallback?.upcoming.orEmpty()
                    }
                    EpgDedupe.collapse(raw.map {
                        EpgProgrammeEntity(sourceId = 0, epgChannelId = channel.id,
                            startMs = it.startUtcMillis, stopMs = it.endUtcMillis,
                            title = it.title, description = it.description)
                    })
                }
                var focused by remember(channel.id) { mutableStateOf(false) }
                fun selected() = programs.firstOrNull { cursor >= it.startMs && cursor < it.stopMs }
                    ?.let { IptvProgram(title = it.title, description = it.description,
                        startUtcMillis = it.startMs, endUtcMillis = it.stopMs) }
                fun moveRow(delta: Int): Boolean {
                    val target = index + delta
                    if (target < 0) {
                        if (channelWindowOffset > 0) onRequestPreviousChannels() else return false
                    } else if (target >= channels.size) {
                        if (channelWindowOffset + channels.size < totalChannelCount) onRequestNextChannels() else return false
                    } else scope.launch { focusRow(target, focused) }
                    return true
                }
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    Box(Modifier.width(columnWidth)) {
                        OwnTvChannelLabel(channel = channel, clockTickMillis = clockTickMillis,
                            nowNext = channelNowNext[channel.id], isActive = channel.id == selectedChannelId,
                            isFavorite = channel.id in favorites, onClick = { onChannelSelect(channel) },
                            onFavoriteToggle = { onChannelFavoriteToggle(channel.id) },
                            onMoveLeft = onMoveLeftFromChannels,
                            onMoveRight = { onEnterEpg(channel); programmeFocus.requestFocus(); true },
                            onMoveUp = { moveRow(-1) }, onMoveDown = { moveRow(1) },
                            onFocused = { onChannelFocused(channel) }, variantCount = variantCountFor(channel),
                            onOpenVariants = { onOpenVariants(channel) }, rowHeight = rowHeight,
                            modifier = Modifier.focusRequester(channelFocus).testTag("guide-channel-${channel.id}"))
                    }
                    val rowEmptyLabel = stringResource(when {
                        channel.id in epgLoadingChannelIds || isGuideBackfillLoading -> R.string.live_placeholder_loading_guide
                        !hasGuideSource -> R.string.live_placeholder_no_guide_source
                        channel.id in epgAttemptedChannelIds -> R.string.live_placeholder_no_guide_matched
                        else -> R.string.live_placeholder_guide_pending
                    })
                    OwnTvProgrammeStrip(programs, viewStart, clockTickMillis,
                        if (focused) cursor else null, pixelsPerMinute, formatter, rowEmptyLabel,
                        Modifier.weight(1f).fillMaxHeight().focusRequester(programmeFocus)
                            .testTag("guide-programmes-${channel.id}")
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onChannelFocused(channel) }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionUp -> moveRow(-1)
                                    Key.DirectionDown -> moveRow(1)
                                    Key.Back -> { onExitEpg(channel); channelFocus.requestFocus(); true }
                                    Key.DirectionLeft -> {
                                        val previous = ownTvGuideLeftStep(programs, cursor, clockTickMillis, start, end)
                                        if (previous == null) {
                                            onExitEpg(channel); channelFocus.requestFocus()
                                        } else {
                                            cursor = previous
                                            if (cursor < viewStart || cursor >= viewStart + 90 * 60_000L) {
                                                viewStart = cursor / 1_800_000L * 1_800_000L
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionRight, Key.MediaRewind, Key.MediaFastForward -> {
                                        cursor = ownTvGuideCursorStep(programs, cursor,
                                            if (event.key == Key.MediaRewind) -1 else 1, start, end)
                                        if (cursor < viewStart || cursor >= viewStart + 90 * 60_000L) {
                                            viewStart = cursor / 1_800_000L * 1_800_000L
                                        }
                                        true
                                    }
                                    Key.Enter, Key.DirectionCenter -> { onProgramSelect(channel, selected()); true }
                                    else -> false
                                }
                            }.focusable()
                            .pointerInput(programs, viewStart, pixelsPerMinute) {
                                detectTapGestures { point ->
                                    cursor = viewStart + (point.x / pixelsPerMinute * 60_000).toLong()
                                    onProgramSelect(channel, selected())
                                }
                            })
                }
            }
        }
    }
}

/** OwnTV's clipped one-Canvas-per-row renderer, with ARVIO's colours and typography. */
@Composable
private fun OwnTvProgrammeStrip(
    programmes: List<EpgProgrammeEntity>, viewStart: Long, now: Long, focusTime: Long?,
    pixelsPerMinute: Float, formatter: DateTimeFormatter, emptyLabel: String, modifier: Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val titleStyle = TextStyle(fontSize = 14.sp, color = Color.White)
    val timeStyle = TextStyle(fontSize = 11.sp, color = Color.LightGray)
    Canvas(modifier) {
        var shownUntil = viewStart
        var drawn = false
        if (focusTime != null && programmes.none { focusTime >= it.startMs && focusTime < it.stopMs }) {
            val gapStart = (programmes.lastOrNull { it.stopMs <= focusTime }?.stopMs ?: viewStart).coerceAtLeast(viewStart)
            val gapEnd = programmes.firstOrNull { it.startMs > focusTime }?.startMs ?: (viewStart + 8 * 3_600_000L)
            val left = ((gapStart - viewStart) / 60_000f * pixelsPerMinute).coerceIn(0f, size.width)
            val right = ((gapEnd - viewStart) / 60_000f * pixelsPerMinute).coerceIn(0f, size.width)
            if (right > left) drawRoundRect(Color(0xFF51428F), Offset(left, 2f),
                Size(right - left, size.height - 4), CornerRadius(8f))
        }
        programmes.forEach { programme ->
            val from = maxOf(programme.startMs, shownUntil)
            val until = programme.stopMs
            if (until <= from) return@forEach
            shownUntil = until
            val x = (from - viewStart) / 60_000f * pixelsPerMinute + 2
            val width = (until - from) / 60_000f * pixelsPerMinute - 4
            if (width <= 0 || x + width <= 0 || x >= size.width) return@forEach
            drawn = true
            val selected = focusTime != null && focusTime >= programme.startMs && focusTime < until
            val live = now >= programme.startMs && now < until
            val left = maxOf(x, 0f)
            val right = minOf(x + width, size.width)
            drawRoundRect(when { selected -> Color(0xFF51428F); live -> Color(0xFF30314B); else -> TvStageStyle.Surface },
                Offset(left, 2f), Size(right - left, size.height - 4), CornerRadius(8f))
            val textWidth = (right - left - 16).toInt()
            if (textWidth > 0) {
                val title = measurer.measure(programme.title, titleStyle, overflow = TextOverflow.Ellipsis,
                    maxLines = 1, constraints = Constraints(maxWidth = textWidth))
                drawText(title, topLeft = Offset(left + 8, 6f))
                val time = measurer.measure("${formatter.format(Instant.ofEpochMilli(programme.startMs))} – ${formatter.format(Instant.ofEpochMilli(until))}",
                    timeStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = textWidth))
                drawText(time, topLeft = Offset(left + 8, 8f + title.size.height))
            }
        }
        if (!drawn) drawText(measurer.measure(emptyLabel, timeStyle), topLeft = Offset(12f, 12f))
        val nowX = (now - viewStart) / 60_000f * pixelsPerMinute
        if (nowX in 0f..size.width) drawLine(TvStageStyle.Accent, Offset(nowX, 0f), Offset(nowX, size.height), 2f)
    }
}
