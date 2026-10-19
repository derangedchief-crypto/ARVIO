@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.LayoutInflater
import android.content.pm.ActivityInfo
import com.arflix.tv.util.findActivity
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.arflix.tv.R
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.data.model.MediaItem as ArvioMediaItem
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.IptvPlaybackTarget
import com.arflix.tv.data.repository.StalkerPortalSupport
import com.arflix.tv.ui.screens.tv.TvUiState
import com.arflix.tv.ui.screens.tv.TvViewModel
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.KeepScreenOn
import com.arflix.tv.ui.components.AppTopBarHeight
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.topBarSelectedIndex
import com.arflix.tv.util.LocalDeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


private object LiveTvScreenRegexes {
    val IPTV_URL_REDACT_REGEX = Regex("""(?i)(/(?:live|movie|series|timeshift)/)([^/]+)/([^/]+)(/)""")
    val QUALITY_REMOVAL = Regex("""(?i)\b(?:4k|uhd|fhd|hd|sd|1080p|720p|60fps)\b""")
    val MULTI_SPACE = Regex("""\s+""")
    val QUERY_SECRETS = Regex("""(?i)([?&](?:username|user|uname|password|pass|pwd)=)[^&]+""")
}

private enum class LiveTvFocusZone {
    TOPBAR,
    PROVIDER_SWITCHER,
    CATEGORY_LIST,
    CHANNEL_LIST,
    EPG,
}

private const val GuideInitialWindowRows = 48
private const val GuidePageRows = 48
internal const val GuideMaxWindowRows = 144
private const val GuidePagedLoadStepRows = 192
private const val GuideVisibleFirstRows = 28
private const val GuideVisibleFirstRowsAllChannels = 18
private const val CatchupSeekStepMs = 30_000L
private const val CatchupUrlAnchorGranularityMs = 60_000L
private const val IptvPlaybackUserAgent = "VLC/3.0.20 LibVLC/3.0.20"
// Query the same span the widest on-screen grid can actually render. The old
// ±48-hour query inflated every 144-row window into thousands of programme
// objects, most never visible, and made category/focus movement jank badly on
// entry-level TV hardware.
private const val VisibleGuidePastWindowMs = 2L * 60L * 60_000L
private const val VisibleGuideFutureWindowMs = 6L * 60L * 60_000L
private const val GuideViewportBucketMs = 30L * 60_000L
// Keep the current and immediately previous guide pages warm while the next
// indexed query is in flight. This cache is source-scoped and every queried
// channel is replaced (including with empty data) once SQLite responds.
// The on-screen list is virtualized, but the local index is cheap to read in
// batches. Keep a substantial runway around it so D-pad repeats never cause a
// database read or guide-model rebuild on the focused row.
private const val GuideIndexLookaheadRows = 96
private const val RetainedIndexedGuideRows = GuideMaxWindowRows + (GuideIndexLookaheadRows * 2)
private const val EpgGuideLookupTimeoutMs = 2_500L

private fun digitForTvKeyCode(keyCode: Int): Int? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_0, AndroidKeyEvent.KEYCODE_NUMPAD_0 -> 0
    AndroidKeyEvent.KEYCODE_1, AndroidKeyEvent.KEYCODE_NUMPAD_1 -> 1
    AndroidKeyEvent.KEYCODE_2, AndroidKeyEvent.KEYCODE_NUMPAD_2 -> 2
    AndroidKeyEvent.KEYCODE_3, AndroidKeyEvent.KEYCODE_NUMPAD_3 -> 3
    AndroidKeyEvent.KEYCODE_4, AndroidKeyEvent.KEYCODE_NUMPAD_4 -> 4
    AndroidKeyEvent.KEYCODE_5, AndroidKeyEvent.KEYCODE_NUMPAD_5 -> 5
    AndroidKeyEvent.KEYCODE_6, AndroidKeyEvent.KEYCODE_NUMPAD_6 -> 6
    AndroidKeyEvent.KEYCODE_7, AndroidKeyEvent.KEYCODE_NUMPAD_7 -> 7
    AndroidKeyEvent.KEYCODE_8, AndroidKeyEvent.KEYCODE_NUMPAD_8 -> 8
    AndroidKeyEvent.KEYCODE_9, AndroidKeyEvent.KEYCODE_NUMPAD_9 -> 9
    else -> null
}

internal fun selectPagedChannelsInProviderOrder(
    categoryId: String,
    providerWindow: List<IptvChannel>,
    favoriteChannels: List<IptvChannel>,
    recentChannels: List<IptvChannel>,
    limit: Int,
): List<IptvChannel> {
    val source = when (categoryId) {
        "fav" -> favoriteChannels
        "recent" -> recentChannels
        else -> providerWindow
    }
    // Compose may still request an index from the previous lazy-list snapshot
    // while paging replaces the backing list. Never expose a live SubList view:
    // its size can change underneath the item provider and crash older TV ART.
    return source.take(limit.coerceAtLeast(0))
}

/**
 * Live TV screen — Arvio spec §1. Three focus regions: Sidebar ↔ MiniPlayer ↔ EPG.
 * Preserves every IPTV feature from the legacy [com.arflix.tv.ui.screens.tv.TvScreen]
 * (favorites, hidden groups, EPG refresh, cloud sync) — only the UI shell is new.
 */
private fun guideWindowAround(index: Int, total: Int): Pair<Int, Int> {
    if (total <= 0) return 0 to 0
    val safeIndex = index.coerceIn(0, total - 1)
    val before = 0
    val start = (safeIndex - before).coerceAtLeast(0)
    val end = (start + GuideInitialWindowRows).coerceAtMost(total)
    val balancedStart = (end - GuideInitialWindowRows).coerceAtLeast(0)
    return balancedStart to end
}

private fun expandGuideWindowAfter(start: Int, end: Int, total: Int): Pair<Int, Int> {
    if (end >= total) return start to end
    val nextEnd = (end + GuidePageRows).coerceAtMost(total)
    val overflow = (nextEnd - start - GuideMaxWindowRows).coerceAtLeast(0)
    return (start + overflow).coerceAtMost(nextEnd) to nextEnd
}

private fun expandGuideWindowBefore(start: Int, end: Int): Pair<Int, Int> {
    if (start <= 0) return start to end
    val nextStart = (start - GuidePageRows).coerceAtLeast(0)
    val overflow = (end - nextStart - GuideMaxWindowRows).coerceAtLeast(0)
    return nextStart to (end - overflow).coerceAtLeast(nextStart)
}

private fun EnrichedChannel.hasGuideIdentity(): Boolean =
    !source.epgId.isNullOrBlank() || !source.tvgName.isNullOrBlank()

private fun IptvNowNext?.hasGuideData(): Boolean =
    this != null &&
        (now != null || next != null || later != null || upcoming.isNotEmpty() || recent.isNotEmpty())

private fun isSafePlaybackHeader(name: String, value: String): Boolean {
    return name.isNotBlank() &&
        value.isNotBlank() &&
        name.all { ch ->
            ch.code in 33..126 &&
                ch !in setOf('(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}')
        } &&
        value.all { ch -> ch == '\t' || ch.code in 32..126 }
}

private fun Map<String, String>.safePlaybackHeaders(): Map<String, String> {
    if (isEmpty()) return emptyMap()
    return filter { (name, value) -> isSafePlaybackHeader(name.trim(), value.trim()) }
        .mapKeys { (name, _) -> name.trim() }
        .mapValues { (_, value) -> value.trim() }
}

private fun mergeProgramLists(
    first: List<IptvProgram>,
    second: List<IptvProgram>,
): List<IptvProgram> {
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first
    return (first + second)
        .distinctBy { "${it.startUtcMillis}:${it.endUtcMillis}:${it.title}" }
        .sortedBy { it.startUtcMillis }
}

private fun mergeGuideSlices(
    primary: IptvNowNext?,
    secondary: IptvNowNext?,
): IptvNowNext? {
    if (!primary.hasGuideData()) return secondary
    if (!secondary.hasGuideData()) return primary
    primary ?: return secondary
    secondary ?: return primary
    return IptvNowNext(
        now = primary.now ?: secondary.now,
        next = primary.next ?: secondary.next,
        later = primary.later ?: secondary.later,
        upcoming = mergeProgramLists(primary.upcoming, secondary.upcoming),
        recent = mergeProgramLists(primary.recent, secondary.recent),
    )
}

private fun Map<String, List<IptvProgram>>.toNowNextSlices(nowMillis: Long): Map<String, IptvNowNext> =
    buildMap(size) {
        this@toNowNextSlices.forEach { (channelId, programs) ->
            val sorted = programs.asSequence()
                .filter { it.endUtcMillis > it.startUtcMillis }
                .distinctBy { "${it.startUtcMillis}:${it.endUtcMillis}:${it.title}" }
                .sortedBy { it.startUtcMillis }
                .toList()
            if (sorted.isEmpty()) return@forEach
            val now = sorted.lastOrNull { it.isLive(nowMillis) }
            val future = sorted.filter { it.startUtcMillis > nowMillis }
            val recent = sorted.filter { it.endUtcMillis <= nowMillis }
            put(
                channelId,
                IptvNowNext(
                    now = now,
                    next = future.getOrNull(0),
                    later = future.getOrNull(1),
                    upcoming = future,
                    recent = recent,
                ),
            )
        }
    }

private fun EnrichedChannel.guideFallbackKeys(): List<String> {
    val playlistId = StalkerPortalSupport.playlistIdFromChannelId(id)
    val prefix = playlistId.ifBlank { "default" }
    val keys = LinkedHashSet<String>()

    fun addKey(kind: String, value: String?) {
        val normalized = value
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return
        keys += "$prefix|$kind:$normalized"
    }

    addKey("epg", source.epgId)
    addKey("tvg", source.tvgName)
    source.variantKey
        ?.takeIf { it != source.id }
        ?.let { addKey("variant", it) }
    addKey(
        "name",
        name
            .substringAfter('|', missingDelimiterValue = name)
            .replace(LiveTvScreenRegexes.QUALITY_REMOVAL, " ")
            .replace(LiveTvScreenRegexes.MULTI_SPACE, " ")
            .trim()
    )

    return keys.toList()
}

private fun looksLikeMpegTsUrl(url: String): Boolean {
    val lower = url.lowercase()
    val path = lower.substringBefore('?')
    if (path.endsWith(".m3u8") || "output=m3u8" in lower) return false
    if (
        path.endsWith(".ts") ||
        path.endsWith("timeshift.php") ||
        "output=ts" in lower ||
        path.contains("/timeshift/")
    ) return true

    val segments = path
        .substringAfter("://", missingDelimiterValue = "")
        .substringAfter('/', missingDelimiterValue = "")
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
    return segments.size >= 4 &&
        segments.first().equals("live", ignoreCase = true) &&
        segments.last().substringBefore('.').toIntOrNull() != null
}

private fun IptvProgram.shiftedForCatchup(offsetMs: Long): IptvProgram {
    val latestStartOffset = (endUtcMillis - startUtcMillis - 1_000L).coerceAtLeast(0L)
    val safeOffset = offsetMs.coerceIn(0L, latestStartOffset)
    if (safeOffset <= 0L) return this
    return copy(startUtcMillis = (startUtcMillis + safeOffset).coerceAtMost(endUtcMillis - 1_000L))
}

private fun IptvChannel.catchupUrlAnchorOffset(offsetMs: Long): Long {
    val safeOffset = offsetMs.coerceAtLeast(0L)
    val type = catchupType?.trim()?.lowercase().orEmpty()
    val usesMinuteStart = type in setOf("xtream", "xc", "xciptv", "timeshift") ||
        xtreamStreamId != null ||
        streamUrl.contains("/live/", ignoreCase = true)
    return if (usesMinuteStart) {
        safeOffset - (safeOffset % CatchupUrlAnchorGranularityMs)
    } else {
        safeOffset
    }
}

private fun IptvChannel.catchupInSegmentSeekOffset(offsetMs: Long): Long {
    val safeOffset = offsetMs.coerceAtLeast(0L)
    return (safeOffset - catchupUrlAnchorOffset(safeOffset)).coerceAtLeast(0L)
}

private fun EnrichedChannel?.supportsCatchupHistory(): Boolean {
    val source = this?.source ?: return false
    if (source.catchupDays > 0) return true
    if (!source.catchupType.isNullOrBlank() || !source.catchupSource.isNullOrBlank()) return true
    return source.streamUrl.contains("/timeshift/", ignoreCase = true)
        || source.xtreamStreamId != null
        || source.streamUrl.contains("/live/", ignoreCase = true)
}

private fun EnrichedChannel.hasExplicitCatchupSource(): Boolean {
    val source = this.source
    if (source.catchupDays > 0) return true
    if (!source.catchupType.isNullOrBlank() || !source.catchupSource.isNullOrBlank()) return true
    return source.streamUrl.contains("/timeshift/", ignoreCase = true)
}

private fun catchupQualityRank(channel: EnrichedChannel): Int = when (channel.quality) {
    Quality.K4 -> 4
    Quality.FHD -> 3
    Quality.HD -> 2
    Quality.SD -> 1
}

private fun catchupPlaybackVariant(
    channel: EnrichedChannel,
    channels: List<EnrichedChannel>
): EnrichedChannel {
    if (channel.hasExplicitCatchupSource()) return channel
    val key = variantGroupKey(channel)
    return channels
        .asSequence()
        .filter { it.id != channel.id && variantGroupKey(it) == key }
        .filter { it.hasExplicitCatchupSource() }
        .maxWithOrNull(
            compareBy<EnrichedChannel> { it.source.catchupDays }
                .thenBy { catchupQualityRank(it) }
        )
        ?: channel
}

@Composable
fun LiveTvScreen(
    viewModel: TvViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    initialChannelId: String? = null,
    initialStreamUrl: String? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToIptvSettings: (() -> Unit)? = null,
    onNavigateToDetails: (com.arflix.tv.data.model.MediaType, Int) -> Unit = { _, _ -> },
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // Lifecycle-aware collection so the screen stops draining state updates
    // the instant the user backs out — matters on a long-running IPTV flow
    // where the ViewModel pushes EPG refreshes every few seconds.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Durable XMLTV batches advance this even when the large in-memory snapshot
    // is intentionally capped. Refresh just the visible guide viewport rather
    // than waiting for the entire provider guide to finish parsing.
    val guideIndexRevision by viewModel.iptvRepository.guideIndexRevision.collectAsStateWithLifecycle()
    val currentUiState by rememberUpdatedState(state)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val deviceType = LocalDeviceType.current
    val isTouchDevice = deviceType.isTouchDevice()
    val useTouchRail = isTouchDevice && configuration.smallestScreenWidthDp < 600
    val miniPlayerLayout = liveTvMiniPlayerLayout(
        isTouchDevice = isTouchDevice,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
    val compactTouchLayout = isTouchDevice && configuration.screenWidthDp < 900
    val landscapeCompactMiniPlayer = miniPlayerLayout == LiveTvMiniPlayerLayout.LANDSCAPE_COMPACT
    val showTopBar = !isTouchDevice
    val contentTopPadding = if (showTopBar) AppTopBarHeight else 0.dp
    val coroutineScope = rememberCoroutineScope()
    val guideClockMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            // Grid boundaries are minute-granular. A 30-second full-guide
            // recomposition and SQLite read made the remote feel sluggish
            // without changing any displayed programme cell.
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var selectedProviderId by rememberSaveable { mutableStateOf("all") }
    val recents = remember { mutableStateOf<LinkedHashSet<String>>(LinkedHashSet()) }
    val favSet = remember(state.snapshot.favoriteChannels) { state.snapshot.favoriteChannels.toSet() }
    val hiddenGroupSet = remember(state.snapshot.hiddenGroups) { state.snapshot.hiddenGroups.toSet() }
    LaunchedEffect(state.tvSession.recentChannelIds, state.tvSession.lastChannelId) {
        val persistedRecents = state.tvSession.recentChannelIds
            .ifEmpty { listOfNotNull(state.tvSession.lastChannelId.takeIf { it.isNotBlank() }) }
        if (persistedRecents.isNotEmpty()) {
            recents.value = LinkedHashSet<String>().apply {
                persistedRecents.forEach { id ->
                    if (id.isNotBlank()) add(id)
                }
                while (size > 40) remove(first())
            }
        }
    }

    // Enrichment runs on a background dispatcher and is published through state
    // — avoids blocking recomposition for 10k+ playlists. Result is cached in
    // the ViewModel so re-visits to the TV page are instant (no 2-3s stall).
    val enrichedState = remember {
        mutableStateOf<EnrichedChannels>(
            (viewModel.cachedEnrichedChannels as? EnrichedChannels) ?: EnrichedChannels.Empty
        )
    }
    var pagedLoadedLimit by rememberSaveable { mutableIntStateOf(GuideMaxWindowRows) }
    var lastKnownPagedTotal by rememberSaveable { mutableIntStateOf(0) }
    var lastKnownPlaylistGroupCounts by remember {
        mutableStateOf<List<Triple<String, String, Int>>>(emptyList())
    }
    LaunchedEffect(selectedProviderId, selectedCategoryId) {
        pagedLoadedLimit = GuideMaxWindowRows
    }
    LaunchedEffect(state.snapshot.channels, selectedCategoryId, favSet, recents.value, hiddenGroupSet, state.snapshot.groupOrder, pagedLoadedLimit) {
        val snapshot = state.snapshot.channels
        var pagedTotal = withContext(Dispatchers.IO) {
            if (viewModel.iptvRepository.pagedChannelsReady()) {
                viewModel.iptvRepository.pagedChannelCount(null)
            } else {
                0
            }
        }
        if (snapshot.size in 1..500 && !shouldUsePagedChannelStore(snapshot.size, pagedTotal)) {
            var attempt = 0
            while (attempt < 20 && !shouldUsePagedChannelStore(snapshot.size, pagedTotal)) {
                delay(250L)
                pagedTotal = withContext(Dispatchers.IO) {
                    if (viewModel.iptvRepository.pagedChannelsReady()) {
                        viewModel.iptvRepository.pagedChannelCount(null)
                    } else {
                        0
                    }
                }
                if (shouldUsePagedChannelStore(snapshot.size, pagedTotal)) {
                    System.err.println("[IPTV-PagedUI] paged store became ready after ${attempt + 1} checks total=$pagedTotal snapshot=${snapshot.size}")
                }
                attempt++
            }
        }
        val usePagedStore = shouldUsePagedChannelStore(snapshot.size, pagedTotal)
        if (usePagedStore) {
            lastKnownPagedTotal = pagedTotal
        } else if (shouldUsePagedChannelStore(snapshot.size, lastKnownPagedTotal)) {
            // The paged channel store can briefly report "not ready" during a
            // category switch even though the full IPTV index was already shown.
            // Do not let that transient state collapse the guide back to the
            // tiny startup snapshot; keep serving category windows from the
            // paged path using the last verified total.
            pagedTotal = lastKnownPagedTotal
        }
        val hasPagedStoreWindow = shouldUsePagedChannelStore(snapshot.size, pagedTotal)
        System.err.println("[IPTV-PagedUI] snapshot=${snapshot.size} pagedTotal=$pagedTotal category=$selectedCategoryId loadedLimit=$pagedLoadedLimit")
        if (hasPagedStoreWindow) {
            val signature = buildString {
                append("paged:")
                append(pagedTotal)
                append(':')
                append(selectedCategoryId)
                append(':')
                append(favSet.hashCode())
                append(':')
                append(recents.value.hashCode())
                append(':')
                append(hiddenGroupSet.hashCode())
                append(':')
                append(pagedLoadedLimit)
            }
            if (viewModel.cachedChannelsSignature == signature &&
                viewModel.cachedEnrichedChannels is EnrichedChannels
            ) {
                enrichedState.value = viewModel.cachedEnrichedChannels as EnrichedChannels
                return@LaunchedEffect
            }
            val freshGroupCounts = withContext(Dispatchers.IO) { viewModel.iptvRepository.pagedPlaylistGroupCounts() }
            val groupCounts = if (freshGroupCounts.isNotEmpty()) {
                lastKnownPlaylistGroupCounts = freshGroupCounts
                freshGroupCounts
            } else {
                lastKnownPlaylistGroupCounts
            }
            if (freshGroupCounts.isEmpty() && groupCounts.isNotEmpty()) {
                System.err.println("[IPTV-PagedUI] using cached group counts while paged store refreshes")
            }
            fun resolvePagedGroup(tree: LiveCategoryTree): Pair<String, String>? {
                val fromCounts = lastKnownPlaylistGroupCounts
                    .firstOrNull { (playlistId, groupTitle, _) ->
                        playlistGroupCategoryId(playlistId, groupTitle) == selectedCategoryId
                    }
                    ?.let { (playlistId, groupTitle, _) -> playlistId to groupTitle }
                if (fromCounts != null) return fromCounts
                val category = tree.byId(selectedCategoryId)
                return if (category?.playlistId != null && category.playlistGroupName != null) {
                    category.playlistId to category.playlistGroupName
                } else {
                    null
                }
            }
            val previousFavoriteRows = if (selectedCategoryId == "fav") {
                enrichedState.value.index.channelsFor("fav", favSet, recents.value)
            } else {
                emptyList()
            }
            val window = withContext(Dispatchers.IO) {
                val indexedFavoriteChannels = viewModel.iptvRepository
                    .pagedChannelsByIds(favSet)
                    .filterNot { isAdultGroup(it.group, it.name) }
                val favoriteChannels = if (indexedFavoriteChannels.isNotEmpty() || previousFavoriteRows.isEmpty()) {
                    indexedFavoriteChannels
                } else {
                    previousFavoriteRows.map { it.source }
                }
                val recentChannels = viewModel.iptvRepository
                    .pagedChannelsByIds(recents.value.toList().asReversed())
                    .filterNot { isAdultGroup(it.group, it.name) }
                if (selectedCategoryId == "fav") {
                    System.err.println(
                        "[IPTV-PagedWindow] favorites indexed=${indexedFavoriteChannels.size} " +
                            "previous=${previousFavoriteRows.size} window=${favoriteChannels.size}"
                    )
                }
                val pageLimit = pagedLoadedLimit.coerceAtLeast(GuideMaxWindowRows)
                fun scanCategoryWindow(categoryId: String, targetGroupTitle: String?): List<IptvChannel> {
                    if (!categoryId.startsWith("grp:")) return emptyList()
                    val targetPlaylistId = playlistIdFromGroupCategoryId(categoryId)
                    val targetGroupKey = looseIptvGroupKey(targetGroupTitle)
                    val targetCompactGroupKey = compactIptvGroupKey(targetGroupTitle)
                    val out = ArrayList<IptvChannel>(pageLimit)
                    var offset = 0
                    val chunkSize = 1_000
                    while (out.size < pageLimit && offset < pagedTotal) {
                        val chunk = viewModel.iptvRepository.pagedChannelWindow(null, null, offset, chunkSize)
                        if (chunk.isEmpty()) break
                        chunk.forEach { channel ->
                            val rawPlaylistId = channelPlaylistId(channel.id)
                            val categoryMatches = playlistGroupCategoryId(rawPlaylistId, channel.group) == categoryId
                            val samePlaylist = targetPlaylistId == null || rawPlaylistId == targetPlaylistId
                            val looseGroupMatches = samePlaylist && targetGroupKey.isNotBlank() &&
                                looseIptvGroupKey(channel.group) == targetGroupKey
                            val compactGroupMatches = samePlaylist && targetCompactGroupKey.isNotBlank() &&
                                compactIptvGroupKey(channel.group) == targetCompactGroupKey
                            if (categoryMatches || looseGroupMatches || compactGroupMatches) {
                                out += channel
                                if (out.size >= pageLimit) return out
                            }
                        }
                        offset += chunk.size
                    }
                    return out
                }
                when (selectedCategoryId) {
                    "fav", "recent" -> selectPagedChannelsInProviderOrder(
                        categoryId = selectedCategoryId,
                        providerWindow = emptyList(),
                        favoriteChannels = favoriteChannels,
                        recentChannels = recentChannels,
                        limit = pageLimit,
                    )
                    "all" -> selectPagedChannelsInProviderOrder(
                        categoryId = selectedCategoryId,
                        providerWindow = viewModel.iptvRepository.pagedChannelWindow(null, null, 0, pageLimit),
                        favoriteChannels = favoriteChannels,
                        recentChannels = recentChannels,
                        limit = pageLimit,
                    )
                    else -> {
                        val resolvedGroup = resolvePagedGroup(enrichedState.value.tree)
                        val playlistId = resolvedGroup?.first
                        val groupTitle = resolvedGroup?.second
                        System.err.println(
                            "[IPTV-PagedWindow] category=$selectedCategoryId playlist=${playlistId.orEmpty()} " +
                                "group=${groupTitle.orEmpty()} total=$pagedTotal counts=${groupCounts.size}"
                        )
                        if (playlistId != null && groupTitle != null) {
                            val exactWindow = viewModel.iptvRepository
                                .pagedChannelWindow(playlistId, groupTitle, 0, pageLimit)
                            val fallbackWindow = if (exactWindow.isEmpty()) {
                                viewModel.iptvRepository.pagedChannelWindow(null, groupTitle, 0, pageLimit)
                            } else {
                                exactWindow
                            }
                            if (exactWindow.isEmpty()) {
                                System.err.println(
                                    "[IPTV-PagedWindow] exact group empty, fallback group-only " +
                                        "category=$selectedCategoryId group=$groupTitle rows=${fallbackWindow.size}"
                                )
                            }
                            val recoveredWindow = if (fallbackWindow.isEmpty()) {
                                scanCategoryWindow(selectedCategoryId, groupTitle)
                            } else {
                                fallbackWindow
                            }
                            if (fallbackWindow.isEmpty() && recoveredWindow.isNotEmpty()) {
                                System.err.println(
                                    "[IPTV-PagedWindow] recovered category by scan " +
                                        "category=$selectedCategoryId rows=${recoveredWindow.size}"
                                )
                            }
                            selectPagedChannelsInProviderOrder(
                                categoryId = selectedCategoryId,
                                providerWindow = recoveredWindow,
                                favoriteChannels = favoriteChannels,
                                recentChannels = recentChannels,
                                limit = pageLimit,
                            )
                        } else {
                            selectPagedChannelsInProviderOrder(
                                categoryId = selectedCategoryId,
                                providerWindow = viewModel.iptvRepository.pagedChannelWindow(null, null, 0, pageLimit),
                                favoriteChannels = favoriteChannels,
                                recentChannels = recentChannels,
                                limit = pageLimit,
                            )
                        }
                    }
                }
            }
            val value = withContext(Dispatchers.Default) {
                buildPagedStartupChannelState(
                    channels = window,
                    totalChannelCount = pagedTotal,
                    playlistGroupCounts = groupCounts,
                    favorites = favSet,
                    recents = recents.value,
                    hiddenGroups = hiddenGroupSet,
                    groupOrder = state.snapshot.groupOrder,
                )
            }
            enrichedState.value = value
            viewModel.cachedEnrichedChannels = value
            viewModel.cachedChannelsSignature = signature
            return@LaunchedEffect
        }
        if (snapshot.isEmpty()) {
            if (state.isConfigured && enrichedState.value !== EnrichedChannels.Empty) {
                System.err.println(
                    "[IPTV-UI] Skipping transient empty snapshot; reusing previous enriched channels"
                )
                return@LaunchedEffect
            }
            enrichedState.value = EnrichedChannels.Empty
            return@LaunchedEffect
        }
        // Skip re-enrichment if we already have a cache for the same playlist.
        val signature = "${snapshot.size}:${snapshot.firstOrNull()?.id}:${snapshot.lastOrNull()?.id}"
        if (viewModel.cachedChannelsSignature == signature &&
            viewModel.cachedEnrichedChannels is EnrichedChannels
        ) {
            enrichedState.value = viewModel.cachedEnrichedChannels as EnrichedChannels
            return@LaunchedEffect
        }

        val initialValue = withContext(Dispatchers.Default) {
            buildFastStartupChannelState(
                channels = snapshot,
                favorites = favSet,
                recents = recents.value,
                hiddenGroups = hiddenGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        enrichedState.value = initialValue
        if (snapshot.size > 10_000) {
            viewModel.cachedEnrichedChannels = initialValue
            viewModel.cachedChannelsSignature = signature
            return@LaunchedEffect
        }
        val enriched = withContext(Dispatchers.Default) {
            snapshot.mapIndexed { idx, ch -> ch.enrich(idx + 1) }
        }
        val index = withContext(Dispatchers.Default) { buildCategoryIndex(enriched, hiddenGroupSet) }
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = enriched,
                favoritesCount = favSet.count { index.isVisibleNonAdultChannel(it) },
                recentCount = recents.value.count { index.isVisibleNonAdultChannel(it) },
                hiddenGroups = hiddenGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        val value = EnrichedChannels(all = enriched, tree = tree, index = index)
        enrichedState.value = value
        viewModel.cachedEnrichedChannels = value
        viewModel.cachedChannelsSignature = signature
    }
    // Re-evaluate only dynamic counts when favorites/recents change.
    LaunchedEffect(favSet, hiddenGroupSet, state.snapshot.groupOrder, recents.value, enrichedState.value.all) {
        val current = enrichedState.value
        if (current === EnrichedChannels.Empty) return@LaunchedEffect
        val fullAllCount = current.tree.countForCategory("all") ?: current.all.size
        if (fullAllCount > current.all.size) {
            return@LaunchedEffect
        }
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = current.all,
                favoritesCount = favSet.count { current.index.isVisibleNonAdultChannel(it) },
                recentCount = recents.value.count { current.index.isVisibleNonAdultChannel(it) },
                hiddenGroups = hiddenGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        enrichedState.value = current.copy(tree = tree)
    }

    val providerFilters = remember(state.config, enrichedState.value.all, lastKnownPlaylistGroupCounts) {
        buildTvProviderFilters(state.config, enrichedState.value.all, lastKnownPlaylistGroupCounts)
    }
    val playlistCategorySections = remember(state.config, enrichedState.value.tree.global.categories, hiddenGroupSet) {
        buildPlaylistCategorySections(state.config, enrichedState.value.tree.global.categories, hiddenGroupSet)
    }
    LaunchedEffect(playlistCategorySections, selectedProviderId) {
        if (playlistCategorySections.isNotEmpty() && selectedProviderId != "all") {
            selectedProviderId = "all"
        }
    }
    LaunchedEffect(providerFilters, selectedProviderId) {
        if (providerFilters.isEmpty() || providerFilters.none { it.id == selectedProviderId }) {
            selectedProviderId = "all"
        }
    }

    val visibleEnrichedState = remember { mutableStateOf(EnrichedChannels.Empty) }
    LaunchedEffect(
        enrichedState.value,
        selectedProviderId,
        favSet,
        hiddenGroupSet,
        state.snapshot.groupOrder,
        recents.value,
        state.config,
    ) {
        val current = enrichedState.value
        if (current === EnrichedChannels.Empty) {
            visibleEnrichedState.value = EnrichedChannels.Empty
            return@LaunchedEffect
        }
        if (selectedProviderId == "all") {
            visibleEnrichedState.value = current
            return@LaunchedEffect
        }
        val visibleChannels = withContext(Dispatchers.Default) {
            current.all.filter(providerMatcher(selectedProviderId, state.config))
        }
        val index = withContext(Dispatchers.Default) { buildCategoryIndex(visibleChannels, hiddenGroupSet) }
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = visibleChannels,
                favoritesCount = favSet.count { index.isVisibleNonAdultChannel(it) },
                recentCount = recents.value.count { index.isVisibleNonAdultChannel(it) },
                hiddenGroups = hiddenGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        visibleEnrichedState.value = EnrichedChannels(all = visibleChannels, tree = tree, index = index)
    }
    LaunchedEffect(hiddenGroupSet, selectedCategoryId, visibleEnrichedState.value.tree) {
        if (selectedCategoryId != "all" && visibleEnrichedState.value.tree.byId(selectedCategoryId) == null) {
            selectedCategoryId = "all"
        }
    }

    // Selected category (persist across nav). Defaults to "all".
    val hasProfile = currentProfile != null
    val maxTopBarIndex = topBarMaxIndex(hasProfile)
    var focusZone by rememberSaveable { mutableStateOf(LiveTvFocusZone.CATEGORY_LIST) }
    var topBarFocusIndex by rememberSaveable {
        mutableIntStateOf(topBarSelectedIndex(SidebarItem.TV, hasProfile).coerceIn(0, maxTopBarIndex))
    }
    var lastGuideUserNavigationAt by remember { mutableLongStateOf(0L) }
    fun noteGuideUserNavigation() {
        lastGuideUserNavigationAt = System.currentTimeMillis()
    }
    fun isGuideUserNavigating(): Boolean =
        System.currentTimeMillis() - lastGuideUserNavigationAt < 2_500L

    // Category switches are served from prebuilt buckets. Favorites and
    // recents remain ordered dynamic lists, but they are simple id lookups.
    val filteredChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    var filteredChannelsCategoryKey by remember { mutableStateOf<String?>(null) }
    val recentsFilterKey = if (selectedCategoryId == "recent") recents.value else Unit
    LaunchedEffect(
        visibleEnrichedState.value.index,
        visibleEnrichedState.value.tree,
        selectedCategoryId,
        favSet,
        recentsFilterKey,
        pagedLoadedLimit,
        state.snapshot.sortOrder,
    ) {
        val tree = visibleEnrichedState.value.tree
        val categoryCount = tree.countForCategory(selectedCategoryId) ?: 0
        var result = withContext(Dispatchers.Default) {
            visibleEnrichedState.value.index.channelsFor(
                categoryId = selectedCategoryId,
                favorites = state.snapshot.favoriteChannels,
                recents = recents.value,
            )
        }
        // pagedChannelsReady() runs COUNT(*) over the channel database, so it must
        // not be evaluated inline here: this effect body runs on the main
        // dispatcher and the query blocks it on every category switch.
        val pagedStoreReady = result.isEmpty() &&
            categoryCount > 0 &&
            selectedCategoryId.startsWith("grp:") &&
            withContext(Dispatchers.IO) { viewModel.iptvRepository.pagedChannelsReady() }
        if (pagedStoreReady) {
            val resolvedGroup = lastKnownPlaylistGroupCounts
                .firstOrNull { (playlistId, groupTitle, _) ->
                    playlistGroupCategoryId(playlistId, groupTitle) == selectedCategoryId
                }
                ?.let { (playlistId, groupTitle, _) -> playlistId to groupTitle }
                ?: tree.byId(selectedCategoryId)
                    ?.takeIf { it.playlistId != null && it.playlistGroupName != null }
                    ?.let { it.playlistId!! to it.playlistGroupName!! }
            val directChannels = withContext(Dispatchers.IO) {
                val playlistId = resolvedGroup?.first
                val groupTitle = resolvedGroup?.second
                fun scanCategoryWindow(targetGroupTitle: String?): List<IptvChannel> {
                    if (!selectedCategoryId.startsWith("grp:")) return emptyList()
                    val targetPlaylistId = playlistIdFromGroupCategoryId(selectedCategoryId)
                    val targetGroupKey = looseIptvGroupKey(targetGroupTitle)
                    val targetCompactGroupKey = compactIptvGroupKey(targetGroupTitle)
                    val out = ArrayList<IptvChannel>(pagedLoadedLimit)
                    var offset = 0
                    val chunkSize = 1_000
                    while (out.size < pagedLoadedLimit) {
                        val chunk = viewModel.iptvRepository.pagedChannelWindow(null, null, offset, chunkSize)
                        if (chunk.isEmpty()) break
                        chunk.forEach { channel ->
                            val rawPlaylistId = channelPlaylistId(channel.id)
                            val categoryMatches = playlistGroupCategoryId(rawPlaylistId, channel.group) == selectedCategoryId
                            val samePlaylist = targetPlaylistId == null || rawPlaylistId == targetPlaylistId
                            val looseGroupMatches = samePlaylist && targetGroupKey.isNotBlank() &&
                                looseIptvGroupKey(channel.group) == targetGroupKey
                            val compactGroupMatches = samePlaylist && targetCompactGroupKey.isNotBlank() &&
                                compactIptvGroupKey(channel.group) == targetCompactGroupKey
                            if (categoryMatches || looseGroupMatches || compactGroupMatches) {
                                out += channel
                                if (out.size >= pagedLoadedLimit) return out
                            }
                        }
                        offset += chunk.size
                    }
                    return out
                }
                if (playlistId != null && groupTitle != null) {
                    val exact = viewModel.iptvRepository
                        .pagedChannelWindow(playlistId, groupTitle, 0, pagedLoadedLimit)
                    if (exact.isNotEmpty()) {
                        exact
                    } else {
                        scanCategoryWindow(groupTitle)
                    }
                } else {
                    scanCategoryWindow(tree.byId(selectedCategoryId)?.playlistGroupName)
                }
            }
            if (directChannels.isNotEmpty()) {
                System.err.println(
                    "[IPTV-PagedWindow] recovered empty filtered category=$selectedCategoryId " +
                        "rows=${directChannels.size}/$categoryCount"
                )
                result = withContext(Dispatchers.Default) {
                    directChannels.mapIndexed { index, channel -> channel.enrichForFastStartup(index + 1) }
                }
            }
        }
        if (result.isEmpty() &&
            categoryCount > 0 &&
            filteredChannelsCategoryKey == selectedCategoryId &&
            filteredChannelsState.value.isNotEmpty()
        ) {
            System.err.println(
                "[IPTV-PagedWindow] keeping previous filtered rows while category window rebuilds " +
                    "category=$selectedCategoryId count=$categoryCount"
            )
            return@LaunchedEffect
        }
        filteredChannelsCategoryKey = selectedCategoryId
        filteredChannelsState.value = sortChannelsByConfiguredOrder(result, state.snapshot.sortOrder)
    }
    val visibleChannels = visibleEnrichedState.value.all
    // Variant grouping + collapsing + index building are O(channels). Doing them
    // synchronously in composition froze the main thread for ~10s on very large
    // playlists (50k+ channels) — the cause of janky navigation AND live-TV
    // buffering (a frozen main thread starves the player) AND the guide appearing
    // to "not load" (arriving EPG state couldn't be rendered while frozen).
    // Compute them on a background dispatcher and publish via state. Downstream
    // code tolerates an empty map/list for the one frame before this fills.
    // For very large playlists, building variant groups + collapsed copies creates
    // several more full-size (50k+) collections. With the device heap capped at
    // 384MB that tips it into a blocking-GC spiral (multi-second main-thread freezes
    // = janky nav + live-TV buffering + the guide unable to render). Above this
    // threshold we skip variant work entirely and show channels uncollapsed, reusing
    // the existing lists (no extra copies). Smaller lists keep variant collapsing.
    val variantCollapseLimit = 8_000
    val variantGroupsState = remember { mutableStateOf<Map<String, List<EnrichedChannel>>>(emptyMap()) }
    val allDisplayChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    LaunchedEffect(visibleChannels) {
        if (visibleChannels.isEmpty()) {
            variantGroupsState.value = emptyMap()
            allDisplayChannelsState.value = emptyList()
            return@LaunchedEffect
        }
        if (visibleChannels.size > variantCollapseLimit) {
            variantGroupsState.value = emptyMap()
            allDisplayChannelsState.value = visibleChannels
            return@LaunchedEffect
        }
        val groups = withContext(Dispatchers.Default) { buildVariantGroups(visibleChannels) }
        val collapsed = withContext(Dispatchers.Default) { collapseChannelVariants(visibleChannels, groups) }
        variantGroupsState.value = groups
        allDisplayChannelsState.value = collapsed
    }
    val variantGroups = variantGroupsState.value
    val allDisplayChannels = allDisplayChannelsState.value

    val filteredChannelsCollapsedState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    val filteredChannelIndexState = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(filteredChannelsState.value, variantGroups) {
        val source = filteredChannelsState.value
        // No variant groups (large list) → reuse the source list as-is, no extra copy.
        val collapsed = if (variantGroups.isEmpty()) {
            source
        } else {
            withContext(Dispatchers.Default) { collapseChannelVariants(source, variantGroups) }
        }
        val index = withContext(Dispatchers.Default) {
            HashMap<String, Int>(collapsed.size).apply {
                collapsed.forEachIndexed { idx, channel -> put(channel.id, idx) }
            }
        }
        filteredChannelsCollapsedState.value = collapsed
        filteredChannelIndexState.value = index
    }
    val filteredChannels = filteredChannelsCollapsedState.value
    val filteredChannelIndexById = filteredChannelIndexState.value
    val selectedCategoryTotalCount = remember(visibleEnrichedState.value.tree, selectedCategoryId, filteredChannels.size) {
        visibleEnrichedState.value.tree.countForCategory(selectedCategoryId)
            ?.takeIf { it > 0 }
            ?: filteredChannels.size
    }
    val visibleChannelsById = visibleEnrichedState.value.index.byId
    fun guideForChannel(channel: EnrichedChannel?): IptvNowNext? {
        if (channel == null) return null
        return state.snapshot.nowNext[channel.id]
    }
    // Only an explicit route is allowed to begin playback. Opening the TV tab
    // itself is browse-first: restore focus/guide context, but do not open a
    // network stream until the user selects a channel.
    val rememberedChannelByCategory = remember { mutableMapOf<String, String>() }
    var playingChannelId by rememberSaveable {
        mutableStateOf(initialChannelId?.takeIf(LiveTvStartup::shouldAutoPlay))
    }
    KeepScreenOn(active = playingChannelId != null)
    LaunchedEffect(playingChannelId) {
        viewModel.setLiveTvPlaybackActive(playingChannelId != null)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setLiveTvPlaybackActive(false) }
    }
    // Open on the channel the user last watched. The session already persists
    // lastChannelId, but nothing consumed it on entry, so Live TV always
    // started at the top of the list. Rules live in LiveTvStartup so they are
    // unit tested rather than only verifiable on a device.
    val resumeChannelId = LiveTvStartup.resumeChannelId(
        explicitChannelId = initialChannelId,
        lastChannelId = state.tvSession.lastChannelId,
        availableChannelIds = LiveTvStartup.channelIds(state.snapshot.channels),
    )
    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(resumeChannelId) }
    var epgPrefetchAnchorId by rememberSaveable { mutableStateOf<String?>(resumeChannelId) }
    var startupChannelApplied by rememberSaveable(selectedProviderId) { mutableStateOf(false) }
    var playingCatchupProgram by remember { mutableStateOf<IptvProgram?>(null) }
    var catchupPlaybackOffsetMs by remember { mutableLongStateOf(0L) }
    val focusCommitScope = rememberCoroutineScope()
    val pendingFocusCommit = remember { arrayOf<Pair<String, String>?>(null) }
    val focusCommitJob = remember { arrayOf<Job?>(null) }
    fun commitFocusedChannel(channel: EnrichedChannel) {
        pendingFocusCommit[0] = channel.id to selectedCategoryId
        focusCommitJob[0]?.cancel()
        focusCommitJob[0] = focusCommitScope.launch {
            // Focus now hydrates only one indexed row, so it is safe to follow normal
            // D-pad movement closely instead of making the user move beyond a visible
            // page before the guide catches up.
            delay(80L)
            val (channelId, categoryId) = pendingFocusCommit[0] ?: return@launch
            if (focusedChannelId != channelId) {
                focusedChannelId = channelId
            }
            epgPrefetchAnchorId = channelId
            rememberedChannelByCategory[categoryId] = channelId
        }
    }
    DisposableEffect(Unit) {
        onDispose { focusCommitJob[0]?.cancel() }
    }
    val selectedDisplayChannelId = remember(focusedChannelId, playingChannelId, visibleChannelsById, variantGroups) {
        displayChannelIdFor(focusedChannelId ?: playingChannelId, visibleChannelsById, variantGroups)
    }
    val indexedPlayingChannel = remember(playingChannelId, visibleEnrichedState.value, filteredChannels) {
        playingChannelId?.let { visibleEnrichedState.value.index.byId[it] }
            ?: filteredChannels.firstOrNull { it.id == playingChannelId }
    }
    var retainedPlayingChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    LaunchedEffect(playingChannelId, indexedPlayingChannel) {
        retainedPlayingChannel = when {
            playingChannelId == null -> null
            indexedPlayingChannel != null -> indexedPlayingChannel
            retainedPlayingChannel?.id == playingChannelId -> retainedPlayingChannel
            else -> retainedPlayingChannel
        }
    }
    val playingChannel = indexedPlayingChannel ?: retainedPlayingChannel?.takeIf { it.id == playingChannelId }
    val catchupUrlAnchorOffsetMs = remember(playingChannel?.source, catchupPlaybackOffsetMs) {
        playingChannel?.source?.catchupUrlAnchorOffset(catchupPlaybackOffsetMs) ?: 0L
    }
    val catchupInSegmentSeekMs = remember(playingChannel?.source, catchupPlaybackOffsetMs) {
        playingChannel?.source?.catchupInSegmentSeekOffset(catchupPlaybackOffsetMs) ?: 0L
    }
    val currentNowNext = remember(playingChannel, playingCatchupProgram, state.snapshot.nowNext) {
        val live = guideForChannel(playingChannel)
        val catchup = playingCatchupProgram
        if (catchup != null) {
            com.arflix.tv.data.model.IptvNowNext(
                now = catchup,
                next = null,
                later = null,
                upcoming = emptyList(),
                recent = emptyList()
            )
        } else {
            live
        }
    }

    var guideWindowStart by rememberSaveable { mutableIntStateOf(0) }
    var guideWindowEnd by rememberSaveable { mutableIntStateOf(GuideInitialWindowRows) }
    val guidePageRequestGate = remember { TvGuidePageRequestGate() }
    fun setGuideWindow(window: Pair<Int, Int>) {
        val total = filteredChannels.size
        val start = window.first.coerceIn(0, total.coerceAtLeast(0))
        val end = window.second.coerceIn(start, total)
        guideWindowStart = start
        guideWindowEnd = end
    }
    fun requestGuideWindowBefore() {
        if (!guidePageRequestGate.shouldRequest(TvGuideDirection.UP, System.currentTimeMillis())) return
        setGuideWindow(expandGuideWindowBefore(guideWindowStart, guideWindowEnd))
    }
    fun requestGuideWindowAfter() {
        if (!guidePageRequestGate.shouldRequest(TvGuideDirection.DOWN, System.currentTimeMillis())) return
        val hasMorePagedRows = selectedCategoryTotalCount > filteredChannels.size
        if (hasMorePagedRows && guideWindowEnd >= (filteredChannels.size - GuidePageRows).coerceAtLeast(0)) {
            pagedLoadedLimit = (pagedLoadedLimit + GuidePagedLoadStepRows)
                .coerceAtMost(selectedCategoryTotalCount)
                .coerceAtLeast(GuideMaxWindowRows)
        }
        val availableRows = maxOf(filteredChannels.size, pagedLoadedLimit.coerceAtMost(selectedCategoryTotalCount))
        setGuideWindow(expandGuideWindowAfter(guideWindowStart, guideWindowEnd, availableRows))
    }
    val filteredChannelsWindowKey = remember(filteredChannels) {
        listOf(
            filteredChannels.size.toString(),
            filteredChannels.firstOrNull()?.id.orEmpty(),
            filteredChannels.lastOrNull()?.id.orEmpty(),
        ).joinToString("|")
    }
    var guideScopeKey by rememberSaveable { mutableStateOf("") }
    val guideViewportMemory = remember { TvGuideViewportMemory() }
    LaunchedEffect(selectedProviderId, selectedCategoryId) {
        guideViewportMemory.remember(guideScopeKey, guideWindowStart, guideWindowEnd)
        guideScopeKey = ""
        guideWindowStart = 0
        guideWindowEnd = GuideInitialWindowRows
    }
    LaunchedEffect(selectedProviderId, selectedCategoryId, filteredChannelsWindowKey) {
        if (filteredChannels.isEmpty()) return@LaunchedEffect
        val nextScopeKey = "$selectedProviderId|$selectedCategoryId"
        if (guideScopeKey != nextScopeKey) {
            val rememberedWindow = guideViewportMemory.restore(nextScopeKey)
            if (rememberedWindow != null) {
                setGuideWindow(rememberedWindow.first to (rememberedWindow.last + 1))
            } else {
                val anchorId = rememberedChannelByCategory[selectedCategoryId]
                    ?: focusedChannelId
                    ?: playingChannelId
                    ?: initialChannelId
                val anchorIndex = anchorId?.let(filteredChannelIndexById::get) ?: 0
                setGuideWindow(guideWindowAround(anchorIndex, filteredChannels.size))
            }
            guideScopeKey = nextScopeKey
        } else if (guideWindowStart >= filteredChannels.size) {
            setGuideWindow(guideWindowAround(filteredChannels.lastIndex, filteredChannels.size))
        } else if (!isGuideUserNavigating() && guideWindowEnd <= guideWindowStart) {
            val anchorIndex = focusedChannelId?.let(filteredChannelIndexById::get)
                ?: playingChannelId?.let(filteredChannelIndexById::get)
                ?: 0
            setGuideWindow(guideWindowAround(anchorIndex, filteredChannels.size))
        }
    }
    LaunchedEffect(playingChannelId, selectedCategoryId, selectedProviderId) {
        if (isGuideUserNavigating() && (focusZone == LiveTvFocusZone.CHANNEL_LIST || focusZone == LiveTvFocusZone.EPG)) {
            return@LaunchedEffect
        }
        val index = playingChannelId?.let(filteredChannelIndexById::get) ?: return@LaunchedEffect
        if (index !in guideWindowStart until guideWindowEnd) {
            setGuideWindow(guideWindowAround(index, filteredChannels.size))
        }
    }
    val normalizedGuideStart = if (filteredChannels.isNotEmpty() && guideWindowStart >= filteredChannels.size) {
        0
    } else {
        guideWindowStart.coerceIn(0, filteredChannels.size)
    }
    val normalizedGuideEnd = guideWindowEnd.coerceIn(normalizedGuideStart, filteredChannels.size)
    val guideChannels = remember(filteredChannels, normalizedGuideStart, normalizedGuideEnd) {
        val total = filteredChannels.size
        val start = normalizedGuideStart.coerceIn(0, total)
        val end = normalizedGuideEnd.coerceIn(start, total)
        if (start >= end) {
            emptyList()
        } else {
            filteredChannels.subList(start, end).toList()
        }
    }
    LaunchedEffect(selectedCategoryId, filteredChannels.size, guideChannels.size, selectedCategoryTotalCount) {
        if (filteredChannels.isNotEmpty()) {
            System.err.println(
                "[TV-Metrics] category=$selectedCategoryId loaded=${filteredChannels.size}/$selectedCategoryTotalCount " +
                    "guideWindow=${guideChannels.size} start=$normalizedGuideStart"
            )
        }
    }
    val guideChannelIndexById = remember(guideChannels) {
        HashMap<String, Int>(guideChannels.size).apply {
            guideChannels.forEachIndexed { index, channel -> put(channel.id, index) }
        }
    }
    // A full backfill can be intentionally deferred, while the currently
    // displayed rows are already fetching their own guide. Mobile must expose
    // both states; otherwise it looks as though tapping a category did nothing.
    val visibleGuideLoadInProgress = state.epgBackfillInProgress || guideChannels.any { channel ->
        channel.id in state.epgLoadingChannelIds
    }
    val visibleGuideLoadMessage = state.epgBackfillMessage
        ?: if (visibleGuideLoadInProgress) "Loading visible guide" else null
    var retainedIndexedGuidePrograms by remember(selectedProviderId) {
        mutableStateOf<Map<String, List<IptvProgram>>>(emptyMap())
    }
    val guideViewportRequestGate = remember { TvGuideViewportRequestGate() }
    val guideViewportStartMs = remember(guideClockMillis) {
        guideClockMillis - (guideClockMillis % GuideViewportBucketMs) - VisibleGuidePastWindowMs
    }
    val guideViewportEndMs = remember(guideViewportStartMs) {
        guideViewportStartMs + VisibleGuidePastWindowMs + VisibleGuideFutureWindowMs
    }
    // Read a rolling runway from the full local EPG index rather than querying
    // one newly visible row for each D-pad press. The guide stays virtualized,
    // but fast scrolling now lands on already-hydrated programme cells.
    val guideViewportChannelIds = remember(
        filteredChannels,
        normalizedGuideStart,
        normalizedGuideEnd,
    ) {
        if (filteredChannels.isEmpty()) {
            emptySet()
        } else {
            val beforeStart = (normalizedGuideStart - GuideIndexLookaheadRows).coerceAtLeast(0)
            val afterEnd = (normalizedGuideEnd + GuideIndexLookaheadRows).coerceAtMost(filteredChannels.size)
            // Add the current guide window last. If the bounded retained map
            // needs to evict a row, lookahead goes first — never the rows the
            // user is looking at right now.
            buildSet {
                filteredChannels.subList(beforeStart, normalizedGuideStart).forEach { add(it.id) }
                filteredChannels.subList(normalizedGuideEnd, afterEnd).forEach { add(it.id) }
                filteredChannels.subList(normalizedGuideStart, normalizedGuideEnd).forEach { add(it.id) }
            }
        }
    }
    val guideViewportCacheKey = "$selectedProviderId|$selectedCategoryId|$guideViewportStartMs|$guideIndexRevision"
    var requestedGuideViewportIds by remember(guideViewportCacheKey) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(guideViewportChannelIds, guideViewportCacheKey) {
        val requestId = guideViewportRequestGate.beginRequest()
        val ids = guideViewportChannelIds - requestedGuideViewportIds
        if (ids.isEmpty()) {
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        val indexed = withContext(Dispatchers.IO) {
            viewModel.iptvRepository.indexedGuideViewport(ids, guideViewportStartMs, guideViewportEndMs)
        }
        if (!guideViewportRequestGate.isCurrent(requestId)) return@LaunchedEffect
        requestedGuideViewportIds = requestedGuideViewportIds + ids
        // Do not clear old cells when switching a category, returning from the
        // player, or receiving an incremental parser revision. Replace only
        // the rows SQLite has just authoritatively answered; that preserves a
        // correct last frame until its replacement is ready, without keeping
        // stale programme mappings after the query completes.
        val retained = LinkedHashMap(retainedIndexedGuidePrograms)
        ids.forEach(retained::remove)
        retained.putAll(indexed.programsByChannel)
        // Re-touch the actual rendered window after each batch. The buffer is
        // allowed to roll away as navigation advances, but currently displayed
        // rows must remain resident even while the user holds Down/Up.
        guideChannels.forEach { channel ->
            retained.remove(channel.id)?.let { programs -> retained[channel.id] = programs }
        }
        while (retained.size > RetainedIndexedGuideRows) {
            retained.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
        retainedIndexedGuidePrograms = retained
        System.err.println(
            "[TV-Metrics] indexed guide visible=${indexed.programsByChannel.size}/${ids.size} " +
                "rows=${guideChannels.size} generation=${indexed.sourceGeneration} " +
                "status=${indexed.sourceStatus} in ${System.currentTimeMillis() - startedAt}ms"
        )
    }
    val indexedGuideNowNext = remember(retainedIndexedGuidePrograms, guideClockMillis) {
        retainedIndexedGuidePrograms.toNowNextSlices(guideClockMillis)
    }
    val effectiveGuideNowNext = remember(state.snapshot.nowNext, indexedGuideNowNext, guideChannels) {
        HashMap<String, IptvNowNext>(guideChannels.size).apply {
            guideChannels.forEach { channel ->
                mergeGuideSlices(
                    indexedGuideNowNext[channel.id],
                    state.snapshot.nowNext[channel.id]
                )?.let { put(channel.id, it) }
            }
        }
    }
    val actionGuideNowNext = remember(state.snapshot.nowNext, effectiveGuideNowNext) {
        HashMap(state.snapshot.nowNext).apply { putAll(effectiveGuideNowNext) }
    }
    val guideIdentityKeysByChannelId = remember(enrichedState.value.all) {
        enrichedState.value.all.associate { channel ->
            channel.id to guideIdentityKeys(
                channel.source.epgId,
                channel.source.tvgName,
                channel.source.rawTitle,
                channel.name,
            )
        }
    }
    val currentActionGuideNowNext by rememberUpdatedState(actionGuideNowNext)
    val currentGuideIdentityKeysByChannelId by rememberUpdatedState(guideIdentityKeysByChannelId)
    fun currentProgramForAction(channel: EnrichedChannel): IptvProgram? = guideProgramForAction(
        channelId = channel.id,
        guideIdentityKeys = guideIdentityKeys(
            channel.source.epgId,
            channel.source.tvgName,
            channel.source.rawTitle,
            channel.name,
        ),
        guideByChannelId = currentActionGuideNowNext,
        guideIdentityKeysByChannelId = currentGuideIdentityKeysByChannelId,
    )

    val epgAnchorChannelId = epgPrefetchAnchorId
        ?: selectedDisplayChannelId
        ?: focusedChannelId
        ?: playingChannelId
    val epgPrefetchIds = remember(epgAnchorChannelId, selectedDisplayChannelId, focusedChannelId, playingChannelId) {
        listOfNotNull(epgAnchorChannelId ?: selectedDisplayChannelId ?: focusedChannelId ?: playingChannelId)
    }
    LaunchedEffect(selectedCategoryId, epgPrefetchIds, epgAnchorChannelId, state.iptvPreferencesLoaded, state.tvSessionLoaded, state.tvSession.lastChannelId, guideChannelIndexById, startupChannelApplied, playingChannelId, selectedDisplayChannelId, focusedChannelId) {
        // The indexed snapshot already serves the guide viewport. Keep all
        // optional network/cache prefetch off the playback path so browsing
        // channels cannot steal CPU or bandwidth from the active stream.
        if (playingChannelId != null) return@LaunchedEffect
        val startupReady = state.iptvPreferencesLoaded && state.tvSessionLoaded
        if (startupReady && startupChannelApplied && epgPrefetchIds.isNotEmpty()) {
            val selectedId = epgAnchorChannelId
                ?: selectedDisplayChannelId
                ?: focusedChannelId
                ?: playingChannelId
                ?: epgPrefetchIds.firstOrNull()
            viewModel.prefetchVisibleCategoryEpg(
                channelIds = epgPrefetchIds,
                selectedChannelId = selectedId,
                eagerLimit = 1,
                backgroundLimit = 1,
                allowFocusedNetworkRefresh = true,
            )
        }
    }
    LaunchedEffect(playingChannelId, state.iptvPreferencesLoaded, state.tvSessionLoaded, startupChannelApplied) {
        // Do not refresh multiple guide rows merely because the user moves the
        // browse focus while video is playing. That competing SQLite/network
        // work was enough to make streams stutter on entry-level TV hardware.
        val selectedId = playingChannelId ?: return@LaunchedEffect
        if (state.iptvPreferencesLoaded && state.tvSessionLoaded && startupChannelApplied) {
            System.err.println("[EPG-Current] playing=$selectedId")
            viewModel.refreshCurrentChannelEpg(selectedId, forceNetworkForLargeList = true)
            viewModel.prefetchVisibleCategoryEpg(
                channelIds = listOf(selectedId),
                selectedChannelId = selectedId,
                eagerLimit = 1,
                backgroundLimit = 1,
            )
        }
    }
    val catchupHistoryAnchorIds = remember(
        epgAnchorChannelId,
        selectedDisplayChannelId,
        focusedChannelId,
        playingChannelId,
        visibleChannelsById,
        visibleChannels,
    ) {
        buildList {
            val candidateIds = if (playingChannelId != null) {
                listOf(playingChannelId)
            } else {
                listOfNotNull(epgAnchorChannelId, selectedDisplayChannelId, focusedChannelId)
            }
            candidateIds
                .forEach { id ->
                    val channel = visibleChannelsById[id] ?: return@forEach
                    if (channel.supportsCatchupHistory() && channel.id !in this) {
                        add(channel.id)
                    }
                    val archiveVariant = catchupPlaybackVariant(channel, visibleChannels)
                    if (archiveVariant.supportsCatchupHistory() && archiveVariant.id !in this) {
                        add(archiveVariant.id)
                    }
                }
        }.take(3)
    }
    LaunchedEffect(catchupHistoryAnchorIds, state.iptvPreferencesLoaded, state.tvSessionLoaded, startupChannelApplied) {
        if (!state.iptvPreferencesLoaded || !state.tvSessionLoaded || !startupChannelApplied) return@LaunchedEffect
        if (catchupHistoryAnchorIds.isEmpty()) return@LaunchedEffect
        delay(120L)
        catchupHistoryAnchorIds.forEach { id ->
            viewModel.refreshCatchupHistoryForChannel(id)
        }
    }
    val guideStatusIds = remember(epgPrefetchIds, guideChannels, visibleChannelsById, effectiveGuideNowNext) {
        epgPrefetchIds
            .ifEmpty { guideChannels.asSequence().map { it.id }.take(96).toList() }
            .filter { id ->
                visibleChannelsById[id]?.hasGuideIdentity() == true ||
                    effectiveGuideNowNext[id].hasGuideData()
            }
            .toCollection(HashSet())
    }
    val matchedGuideCount = remember(effectiveGuideNowNext, guideStatusIds) {
        guideStatusIds.count { id ->
            effectiveGuideNowNext[id]?.let { guide ->
                guide.now != null || guide.next != null || guide.later != null ||
                    guide.upcoming.isNotEmpty() || guide.recent.isNotEmpty()
            } == true
        }
    }
    val guideLoadingInScope = remember(state.epgLoadingChannelIds, guideStatusIds) {
        state.epgLoadingChannelIds.any { it in guideStatusIds }
    }

    // Restore a browse/focus anchor only after saved IPTV preferences/session
    // have loaded. This deliberately does not assign playingChannelId: entering
    // Live TV must not autoplay the last, favourite, or first provider stream.
    LaunchedEffect(filteredChannelsWindowKey, playingChannelId, initialChannelId, state.tvSession, state.snapshot.favoriteChannels, visibleEnrichedState.value.all.size, state.iptvPreferencesLoaded, state.tvSessionLoaded, selectedProviderId, startupChannelApplied) {
        val startupStateReady = state.iptvPreferencesLoaded && state.tvSessionLoaded
        if (!startupChannelApplied && filteredChannels.isNotEmpty() && (initialChannelId != null || startupStateReady)) {
            val startupFocusId = initialChannelId
                ?.takeIf { selectedProviderId == "all" || it in visibleEnrichedState.value.index.byId }
                ?: state.tvSession.lastChannelId?.takeIf { it in filteredChannelIndexById }
                ?: filteredChannels.firstOrNull()?.id
            if (startupFocusId != null) {
                val displayId = displayChannelIdFor(startupFocusId, visibleEnrichedState.value.index.byId, variantGroups)
                    ?: startupFocusId
                focusedChannelId = displayId
                epgPrefetchAnchorId = displayId
                rememberedChannelByCategory[selectedCategoryId] = displayId
                filteredChannelIndexById[displayId]
                    ?.let { setGuideWindow(guideWindowAround(it, filteredChannels.size)) }
                startupChannelApplied = true
                System.err.println("[EPG-Startup] browse-focus=$displayId autoplay=false")
            }
        }
        if ((focusedChannelId == null || focusedChannelId !in filteredChannelIndexById) && !isGuideUserNavigating()) {
            focusedChannelId = displayChannelIdFor(playingChannelId, visibleEnrichedState.value.index.byId, variantGroups)
                ?.takeIf { id -> id in filteredChannelIndexById }
                ?: filteredChannels.firstOrNull()?.id
            epgPrefetchAnchorId = focusedChannelId
        }
    }

    val sidebarExpanded = !useTouchRail
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var focusSelectedChannelSignal by remember { mutableIntStateOf(0) }
    var focusEpgSignal by remember { mutableIntStateOf(0) }
    // Starts at 0 so opening Live TV does NOT slam focus into the channel
    // search field. It seeded to 1, and the sidebar focuses search for any
    // value > 0, so every entry began with the selector trapped in the search
    // box. focusPlaylistSearch() still bumps it when the user actually asks
    // for search.
    var focusSearchCategorySignal by remember { mutableIntStateOf(0) }
    // Bumped to put the selector on the category list (never on search).
    var focusCategoryRailSignal by remember { mutableIntStateOf(0) }
    // Full-screen playback mode — pressing OK on an EPG row expands the
    // mini-player to cover the whole screen. Back collapses back to the grid.
    var isFullScreen by rememberSaveable { mutableStateOf(initialStreamUrl != null) }
    var fullscreenGuideOpen by remember { mutableStateOf(false) }
    var quickZapOpen by remember { mutableStateOf(false) }
    var variantPickerChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    // A second selection on the currently playing programme offers Watch Live
    // and, only after a confident movie/series match, Stream Now.
    var programActionDialog by remember { mutableStateOf<ProgramActionData?>(null) }
    var programActionVodMatch by remember { mutableStateOf<ArvioMediaItem?>(null) }
    var programActionLookupInProgress by remember { mutableStateOf(false) }
    val programActionLookupGuard = remember { EpgVodLookupGuard() }
    val programActionLookupJob = remember { arrayOf<Job?>(null) }
    fun invalidateProgramActionLookup() {
        programActionLookupJob[0]?.cancel()
        programActionLookupJob[0] = null
        programActionLookupGuard.invalidate()
        programActionDialog = null
        programActionVodMatch = null
        programActionLookupInProgress = false
    }
    LaunchedEffect(
        selectedCategoryId,
        selectedProviderId,
        focusedChannelId,
        searchOpen,
        variantPickerChannel,
        isFullScreen,
        fullscreenGuideOpen,
        quickZapOpen,
    ) {
        invalidateProgramActionLookup()
    }
    LaunchedEffect(isFullScreen) {
        onFullscreenChanged(isFullScreen)
    }
    DisposableEffect(Unit) {
        onDispose {
            programActionLookupJob[0]?.cancel()
            programActionLookupGuard.invalidate()
            onFullscreenChanged(false)
        }
    }
    // Focus requesters for the three regions.
    val sidebarFocus = remember { FocusRequester() }
    val providerFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val fsFocus = remember { FocusRequester() }
    val emptyStateButtonFocus = remember { FocusRequester() }
    val sidebarListState = rememberLazyListState()

    var hudPokeSignal by remember { mutableStateOf(0) }
    var isHudVisible by remember { mutableStateOf(false) }
    var guideOpenedFromQuickZap by remember { mutableStateOf(false) }
    var guideChannel by remember { mutableStateOf<EnrichedChannel?>(null) }

    fun getAvailableCategoryIds(tree: LiveCategoryTree): List<String> {
        val list = mutableListOf<String>()
        tree.top.forEach { cat ->
            if (cat.count > 0 || cat.id == "all") {
                list.add(cat.id)
                if (cat.id == "all") {
                    cat.children.forEach { child ->
                        if (child.count > 0) list.add(child.id)
                    }
                }
            }
        }
        tree.global.categories.forEach { cat ->
            if (cat.count > 0) list.add(cat.id)
        }
        tree.countries.categories.forEach { country ->
            if (country.count > 0) {
                list.add(country.id)
                country.children.forEach { child ->
                    if (child.count > 0) list.add(child.id)
                }
            }
        }
        tree.adult.categories.forEach { cat ->
            if (cat.count > 0) list.add(cat.id)
        }
        return list.distinct()
    }

    fun cycleCategory(forward: Boolean) {
        val tree = visibleEnrichedState.value.tree
        val ids = getAvailableCategoryIds(tree)
        if (ids.isEmpty()) return
        val currentIndex = ids.indexOf(selectedCategoryId)
        val nextIndex = if (forward) {
            (currentIndex + 1) % ids.size
        } else {
            (currentIndex - 1 + ids.size) % ids.size
        }
        selectedCategoryId = ids.getOrNull(nextIndex) ?: "all"
    }

    fun openFullscreenGuide() {
        guideChannel = playingChannel
        viewModel.refreshCatchupHistoryForChannel(playingChannelId)
        fullscreenGuideOpen = true
        hudPokeSignal++
    }

    DisposableEffect(activity, isFullScreen, isTouchDevice) {
        if (!isTouchDevice || !isFullScreen) {
            return@DisposableEffect onDispose { }
        }

        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        if (window != null) {
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (previousOrientation != null) {
                activity?.requestedOrientation = previousOrientation
            }
            if (window != null) {
                @Suppress("DEPRECATION")
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    // Prev/next zapping across the full enriched list (not the filtered
    // category) per user spec. Wraps around.
    fun zap(delta: Int) {
        noteGuideUserNavigation()
        val all = allDisplayChannels
        if (all.isEmpty()) return
        val currentDisplayId = displayChannelIdFor(playingChannelId, visibleEnrichedState.value.index.byId, variantGroups)
        val currentIdx = currentDisplayId?.let { id -> all.indexOfFirst { channel -> channel.id == id } } ?: -1
        val start = if (currentIdx >= 0) currentIdx else 0
        val size = all.size
        val nextIdx = ((start + delta) % size + size) % size
        playingChannelId = all[nextIdx].id
        focusedChannelId = all[nextIdx].id
        epgPrefetchAnchorId = all[nextIdx].id
        rememberedChannelByCategory[selectedCategoryId] = all[nextIdx].id
        playingCatchupProgram = null
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
    }

    fun focusPlaylistSearch() {
        noteGuideUserNavigation()
        focusZone = LiveTvFocusZone.CATEGORY_LIST
        focusSearchCategorySignal += 1
        runCatching { sidebarFocus.requestFocus() }
    }

    fun focusSelectedCategory() {
        noteGuideUserNavigation()
        focusZone = LiveTvFocusZone.CATEGORY_LIST
        // The sidebar owns a requester for the active category. Signal it
        // rather than focusing the sidebar container, whose first focusable
        // child is Search.
        focusCategoryRailSignal += 1
        runCatching { sidebarFocus.requestFocus() }
    }

    // Keep focus in the sidebar while that zone is active — but NOT while the
    // channel list is still loading. During a load the list is recomposing
    // underneath the focused item, so Compose keeps dropping focus and this
    // effect kept re-grabbing it: pressing a direction key while loading sent
    // the selector jumping in unrelated directions, and it stayed pinned to the
    // search field until everything had finished. Once channels exist the
    // layout is stable and normal focus handling behaves predictably.
    val channelsReady = currentUiState.snapshot.channels.isNotEmpty()
    LaunchedEffect(focusZone, isTouchDevice, channelsReady) {
        if (LiveTvStartup.shouldClaimSidebarFocus(
                isTouchDevice = isTouchDevice,
                isCategoryZoneActive = focusZone == LiveTvFocusZone.CATEGORY_LIST,
                channelsLoaded = channelsReady,
            )
        ) {
            runCatching { sidebarFocus.requestFocus() }
        }
    }

    fun focusProviderSwitcher() {
        noteGuideUserNavigation()
        // Playlist sections replace the standalone provider selector. Route focus
        // straight into the category rail when that selector is not composed.
        if (playlistCategorySections.isNotEmpty() || providerFilters.size <= 1) {
            focusPlaylistSearch()
            return
        }
        focusZone = LiveTvFocusZone.PROVIDER_SWITCHER
        runCatching { providerFocus.requestFocus() }
    }

    fun focusChannelList(channelId: String? = focusedChannelId ?: playingChannelId) {
        noteGuideUserNavigation()
        channelId?.let {
            focusedChannelId = it
            epgPrefetchAnchorId = it
            rememberedChannelByCategory[selectedCategoryId] = it
            val index = filteredChannelIndexById[it]
            if (index != null && index !in guideWindowStart until guideWindowEnd) {
                setGuideWindow(guideWindowAround(index, filteredChannels.size))
            }
        }
        focusZone = LiveTvFocusZone.CHANNEL_LIST
        focusSelectedChannelSignal += 1
        runCatching { epgFocus.requestFocus() }
    }

    fun focusEpg(channelId: String) {
        noteGuideUserNavigation()
        focusedChannelId = channelId
        epgPrefetchAnchorId = channelId
        rememberedChannelByCategory[selectedCategoryId] = channelId
        val index = filteredChannelIndexById[channelId]
        if (index != null && index !in guideWindowStart until guideWindowEnd) {
            setGuideWindow(guideWindowAround(index, filteredChannels.size))
        }
        focusZone = LiveTvFocusZone.EPG
        focusEpgSignal += 1
        runCatching { epgFocus.requestFocus() }
    }

    fun exitFullScreenPlayback() {
        val returnFocusChannelId = playingChannelId ?: focusedChannelId
        fullscreenGuideOpen = false
        isFullScreen = false
        hudPokeSignal++
        focusCommitScope.launch {
            // Let the fullscreen layer start collapsing before returning focus
            // to the large guide. On big IPTV lists this keeps Back immediate.
            delay(16L)
            focusChannelList(returnFocusChannelId)
        }
    }

    fun openVariantPicker(channel: EnrichedChannel) {
        noteGuideUserNavigation()
        if (variantCountFor(channel, variantGroups) > 1) {
            variantPickerChannel = channel
        }
    }

    fun playVariant(channel: EnrichedChannel) {
        noteGuideUserNavigation()
        val displayId = displayChannelIdFor(channel.id, visibleEnrichedState.value.index.byId, variantGroups) ?: channel.id
        playingChannelId = channel.id
        focusedChannelId = displayId
        epgPrefetchAnchorId = displayId
        rememberedChannelByCategory[selectedCategoryId] = displayId
        playingCatchupProgram = null
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
        focusChannelList(displayId)
    }

    fun playProgramInMini(channel: EnrichedChannel, program: IptvProgram?) {
        noteGuideUserNavigation()
        val playbackChannel = if (program != null) {
            catchupPlaybackVariant(channel, visibleChannels)
        } else {
            channel
        }
        if (program != null && playbackChannel.id != channel.id) {
            System.err.println(
                "[IPTV-Catchup] using archive variant source=${channel.id} playback=${playbackChannel.id} " +
                    "quality=${playbackChannel.quality.label} days=${playbackChannel.catchupDays}"
            )
        }
        focusedChannelId = playbackChannel.id
        epgPrefetchAnchorId = playbackChannel.id
        rememberedChannelByCategory[selectedCategoryId] = playbackChannel.id
        playingChannelId = playbackChannel.id
        playingCatchupProgram = program
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
        focusChannelList(playbackChannel.id)
    }

    fun isSamePlayingChannel(channel: EnrichedChannel): Boolean {
        val currentDisplayId = displayChannelIdFor(
            playingChannelId,
            visibleEnrichedState.value.index.byId,
            variantGroups,
        )
        return channel.id == playingChannelId || channel.id == currentDisplayId
    }

    fun playLiveFullscreen(channel: EnrichedChannel) {
        invalidateProgramActionLookup()
        noteGuideUserNavigation()
        playingChannelId = channel.id
        focusedChannelId = channel.id
        epgPrefetchAnchorId = channel.id
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        playingCatchupProgram = null
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
        isFullScreen = true
        hudPokeSignal++
    }

    /**
     * Get the current live programme for a channel, using the same guide data
     * the EPG grid is already displaying. This is the direct source — no identity-
     * key aliasing or cross-playlist matching. If the grid shows a programme,
     * this returns it; if the grid shows "guide pending", this returns null.
     */
    fun displayedCurrentProgram(channel: EnrichedChannel): IptvProgram? =
        effectiveGuideNowNext[channel.id]?.now?.takeIf { it.isLive(guideClockMillis) }

    /**
     * When the user second-clicks a playing channel but EPG data hasn't loaded
     * yet (common when switching to a different playlist), trigger an immediate
     * network EPG fetch for that channel, then attempt the VOD resolution.
     * This prevents the feature from silently falling back to fullscreen.
     */
    fun resolveVodWithEagerFetch(channel: EnrichedChannel) {
        invalidateProgramActionLookup()
        if (!epgChannelAllowsVodSearch(channel.name, channel.source.group)) {
            playLiveFullscreen(channel)
            return
        }
        val lookupGeneration = programActionLookupGuard.beginLookup()
        programActionLookupInProgress = true
        programActionLookupJob[0] = coroutineScope.launch {
            try {
                viewModel.refreshCurrentChannelEpg(channel.id, forceNetworkForLargeList = true)
                val program = displayedCurrentProgram(channel)
                    ?: currentProgramForAction(channel)?.takeIf { it.isLive(guideClockMillis) }
                    ?: awaitLiveEpgProgram(
                        programUpdates = viewModel.uiState.map { uiState ->
                            uiState.snapshot.nowNext[channel.id]?.now
                        },
                        timeoutMillis = EpgGuideLookupTimeoutMs,
                    )
                if (!programActionLookupGuard.isCurrent(lookupGeneration)) return@launch
                if (program == null) {
                    playLiveFullscreen(channel)
                    return@launch
                }
                val match = viewModel.findEpgVodMatch(
                    title = program.title,
                    description = program.description,
                    channelName = channel.name,
                    channelGroup = channel.source.group,
                )
                if (!programActionLookupGuard.isCurrent(lookupGeneration)) return@launch
                if (!epgVodLookupCanPublish(
                        selectedProgram = program,
                        currentProgram = viewModel.uiState.value.snapshot.nowNext[channel.id]?.now
                            ?: displayedCurrentProgram(channel)
                            ?: currentProgramForAction(channel),
                        nowMillis = System.currentTimeMillis(),
                    )
                ) return@launch
                when (vodLookupResolution(match != null)) {
                    EpgInteractionAction.ShowVodDialog -> {
                        programActionVodMatch = match
                        programActionDialog = ProgramActionData(channel, program)
                    }
                    EpgInteractionAction.PlayLiveFullscreen -> playLiveFullscreen(channel)
                    else -> Unit
                }
            } finally {
                if (programActionLookupGuard.isCurrent(lookupGeneration)) {
                    programActionLookupInProgress = false
                    programActionLookupJob[0] = null
                }
            }
        }
    }

    fun resolveVodOrPlayFullscreen(channel: EnrichedChannel, program: IptvProgram) {
        invalidateProgramActionLookup()
        if (!epgChannelAllowsVodSearch(channel.name, channel.source.group)) {
            playLiveFullscreen(channel)
            return
        }
        val lookupGeneration = programActionLookupGuard.beginLookup()
        programActionLookupInProgress = true
        programActionLookupJob[0] = coroutineScope.launch {
            try {
                val match = viewModel.findEpgVodMatch(
                    title = program.title,
                    description = program.description,
                    channelName = channel.name,
                    channelGroup = channel.source.group,
                )
                if (!programActionLookupGuard.isCurrent(lookupGeneration)) return@launch
                if (
                    !epgVodLookupCanPublish(
                        selectedProgram = program,
                        currentProgram = viewModel.uiState.value.snapshot.nowNext[channel.id]?.now
                            ?: currentProgramForAction(channel),
                        nowMillis = System.currentTimeMillis(),
                    )
                ) return@launch
                when (vodLookupResolution(match != null)) {
                    EpgInteractionAction.ShowVodDialog -> {
                        programActionVodMatch = match
                        programActionDialog = ProgramActionData(channel, program)
                    }
                    EpgInteractionAction.PlayLiveFullscreen -> playLiveFullscreen(channel)
                    else -> Unit
                }
            } finally {
                if (programActionLookupGuard.isCurrent(lookupGeneration)) {
                    programActionLookupInProgress = false
                    programActionLookupJob[0] = null
                }
            }
        }
    }

    fun selectChannel(channel: EnrichedChannel, currentProgram: IptvProgram? = null) {
        val sameChannel = isSamePlayingChannel(channel)
        // Selecting the channel that is already playing is the TV-remote
        // fullscreen gesture. Do not send it through the optional VOD lookup:
        // that lookup can open the Watch Live / Stream Now dialog instead.
        if (sameChannel) {
            playLiveFullscreen(channel)
            return
        }
        when (
            channelRowInteractionAction(
                isSamePlayingChannel = sameChannel,
                hasCurrentProgram = currentProgram != null,
                vodActionsEnabled = state.epgVodActionsEnabled,
            )
        ) {
            EpgInteractionAction.PlayLiveMini -> playProgramInMini(channel, null)
            EpgInteractionAction.ResolveVodOrPlayFullscreen ->
                resolveVodOrPlayFullscreen(channel, currentProgram ?: return)
            EpgInteractionAction.PlayLiveFullscreen -> {
                // Second click on the playing channel but no current EPG programme.
                // Instead of going straight to fullscreen, try an eager EPG fetch
                // so the Watch Live / Stream Now dialog can still appear. This is
                // the key fix for channels on non-first playlists where EPG data
                // hasn't been prefetched yet.
                if (sameChannel && state.epgVodActionsEnabled &&
                    epgChannelAllowsVodSearch(channel.name, channel.source.group)
                ) {
                    resolveVodWithEagerFetch(channel)
                } else {
                    playLiveFullscreen(channel)
                }
            }
            else -> Unit
        }
    }

    fun selectEpgProgram(channel: EnrichedChannel, program: IptvProgram) {
        val temporalState = when {
            program.isLive(guideClockMillis) -> EpgTemporalState.Live
            program.endUtcMillis <= guideClockMillis -> EpgTemporalState.Past
            else -> EpgTemporalState.Future
        }
        // EpgGrid only forwards past programmes when catch-up is supported.
        val catchupSupported = temporalState == EpgTemporalState.Past
        when (
            epgProgramInteractionAction(
                temporalState = temporalState,
                isSamePlayingChannel = isSamePlayingChannel(channel),
                isCatchupSupported = catchupSupported,
                vodActionsEnabled = state.epgVodActionsEnabled,
            )
        ) {
            EpgInteractionAction.PlayLiveMini -> playProgramInMini(channel, null)
            EpgInteractionAction.PlayCatchup -> playProgramInMini(channel, program)
            EpgInteractionAction.ResolveVodOrPlayFullscreen -> resolveVodOrPlayFullscreen(channel, program)
            EpgInteractionAction.PlayLiveFullscreen -> playLiveFullscreen(channel)
            EpgInteractionAction.NoOp,
            EpgInteractionAction.ShowVodDialog -> Unit
        }
    }
    fun playProgramInFullscreen(program: IptvProgram?, targetChannel: EnrichedChannel? = null) {
        val channel = targetChannel ?: playingChannel
        if (program != playingCatchupProgram) {
            catchupPlaybackOffsetMs = 0L
        }
        if (channel != null) {
            val playbackChannel = catchupPlaybackVariant(channel, visibleChannels)
            if (playbackChannel.id != playingChannelId) {
                System.err.println(
                    "[IPTV-Catchup] using fullscreen archive variant source=${channel.id} " +
                        "playback=${playbackChannel.id} quality=${playbackChannel.quality.label} " +
                        "days=${playbackChannel.catchupDays}"
                )
                playingChannelId = playbackChannel.id
                focusedChannelId = playbackChannel.id
                epgPrefetchAnchorId = playbackChannel.id
            }
        }
        playingCatchupProgram = program
        fullscreenGuideOpen = false
        isFullScreen = true
        hudPokeSignal++
    }

    // ExoPlayer lifecycle — mirrors the legacy screen's setup verbatim so live
    // IPTV behaviour (buffer, retries, chunkless HLS) stays identical.
    var channelNumberBuffer by remember { mutableStateOf("") }
    var lastChannelDigitAt by remember { mutableStateOf(0L) }

    fun tuneChannelNumber(channel: EnrichedChannel) {
        noteGuideUserNavigation()
        playingChannelId = channel.id
        focusedChannelId = channel.id
        epgPrefetchAnchorId = channel.id
        playingCatchupProgram = null
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        focusChannelList(channel.id)
        hudPokeSignal++
    }

    fun handleChannelNumberDigit(digit: Int): Boolean {
        val now = System.currentTimeMillis()
        val prefix = if (now - lastChannelDigitAt > 1_500L) "" else channelNumberBuffer
        channelNumberBuffer = (prefix + digit.toString()).takeLast(4)
        lastChannelDigitAt = now
        visibleEnrichedState.value.all
            .firstOrNull { it.number.toString() == channelNumberBuffer }
            ?.let {
                tuneChannelNumber(it)
                channelNumberBuffer = ""
            }
        return true
    }

    LaunchedEffect(channelNumberBuffer, visibleEnrichedState.value.all) {
        val query = channelNumberBuffer
        if (query.isBlank()) return@LaunchedEffect
        delay(1_200L)
        if (channelNumberBuffer != query) return@LaunchedEffect
        val target = visibleEnrichedState.value.all
            .filter { it.number.toString().startsWith(query) }
            .take(2)
            .singleOrNull()
        if (target != null) {
            tuneChannelNumber(target)
        }
        channelNumberBuffer = ""
    }

    val iptvHttpClient = remember {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .dns(OkHttpProvider.dns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    val baseRequestHeaders = remember {
        mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "User-Agent" to OkHttpProvider.userAgentOr(IptvPlaybackUserAgent),
            "Connection" to "keep-alive"
        )
    }
    val iptvDataSourceFactory = remember(iptvHttpClient, baseRequestHeaders) {
        OkHttpDataSource.Factory(iptvHttpClient)
            .setUserAgent(OkHttpProvider.userAgentOr(IptvPlaybackUserAgent))
            .setDefaultRequestProperties(baseRequestHeaders)
    }
    val mediaSourceFactory = remember(iptvDataSourceFactory) {
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(iptvDataSourceFactory)
    }
    val livePlaybackBufferProfile = remember(context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        buildLiveTvBufferProfile(
            memoryClassMb = activityManager?.memoryClass ?: 384,
            isLowRamDevice = activityManager?.isLowRamDevice == true
        )
    }
    val exoPlayer = remember(livePlaybackBufferProfile) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                livePlaybackBufferProfile.minBufferMs,
                livePlaybackBufferProfile.maxBufferMs,
                livePlaybackBufferProfile.bufferForPlaybackMs,
                livePlaybackBufferProfile.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(livePlaybackBufferProfile.targetBufferBytes)
            // MUST be false: with time-prioritised thresholds ExoPlayer keeps buffering
            // toward maxBufferMs even past targetBufferBytes. Buffer chunks live on the
            // Java heap, so a 4K live stream could allocate hundreds of MB — pinning the
            // 384MB-capped heap at 0% free. That caused OOM crashes while navigating the
            // Live TV page AND the heavy initial buffering (bandwidth burned prefetching
            // minutes of stream while GC stalls starved the player).
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(livePlaybackBufferProfile.backBufferMs, true)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    // Keep hardware decoding first, but recover on the many
                    // low-cost TV firmware builds whose advertised decoder
                    // cannot actually start a provider stream's profile.
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true)
                    // Synchronous codec queueing avoids frame pacing stalls
                    // observed on affected Amlogic/onn Android TV firmware.
                    .forceDisableMediaCodecAsynchronousQueueing()
                    .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
            )
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    // OwnTV's libmpv engine is a compatibility rung, not a second permanent
    // decoder. It is only activated after Media3 exhausts its controlled
    // recovery, then reuses the same visible PlayerView surface.
    val ownTvMpvFallback = remember(context) { OwnTvMpvFallback(context) }
    var usingOwnTvMpvFallback by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer, ownTvMpvFallback) {
        onDispose {
            ownTvMpvFallback.release()
            exoPlayer.release()
        }
    }

    var playerPositionMs by remember { mutableLongStateOf(0L) }
    var playerDurationMs by remember { mutableLongStateOf(0L) }
    var playerIsPlaying by remember { mutableStateOf(false) }
    var playerPlayWhenReady by remember { mutableStateOf(true) }
    var playerIsBuffering by remember { mutableStateOf(false) }
    LaunchedEffect(exoPlayer, playingCatchupProgram, catchupUrlAnchorOffsetMs, isFullScreen, isHudVisible, usingOwnTvMpvFallback) {
        if (usingOwnTvMpvFallback) {
            if (playingCatchupProgram == null) return@LaunchedEffect
            while (true) {
                playerPositionMs = catchupUrlAnchorOffsetMs + ownTvMpvFallback.positionMs
                playerDurationMs = maxOf(ownTvMpvFallback.durationMs,
                    playingCatchupProgram?.let { it.endUtcMillis - it.startUtcMillis } ?: 0L)
                playerPlayWhenReady = ownTvMpvFallback.playing.value
                delay(500L)
            }
        }
        while (true) {
            // The guide composition is intentionally large. Updating its root
            // state every 1.5 seconds just to advance a hidden live HUD steals
            // render time from the video surface on entry-level TV hardware.
            // Catch-up controls need a progressing timeline, and the visible
            // fullscreen HUD does too; the mini-player does not.
            val needsTimelineUpdates = playingCatchupProgram != null ||
                (isFullScreen && isHudVisible && !fullscreenGuideOpen && !quickZapOpen)
            if (!needsTimelineUpdates) {
                playerIsPlaying = exoPlayer.isPlaying
                playerPlayWhenReady = exoPlayer.playWhenReady
                playerIsBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
                delay(2_500L)
                continue
            }
            val programDuration = playingCatchupProgram
                ?.let { (it.endUtcMillis - it.startUtcMillis).coerceAtLeast(0L) }
                ?: 0L
            val exoDuration = exoPlayer.duration
                .takeIf { it > 0L && it != C.TIME_UNSET }
                ?: 0L
            val duration = maxOf(programDuration, exoDuration)
            playerDurationMs = duration
            val streamOffset = if (playingCatchupProgram != null) catchupUrlAnchorOffsetMs else 0L
            playerPositionMs = (streamOffset + exoPlayer.currentPosition)
                .coerceAtLeast(0L)
                .let { position -> if (duration > 0L) position.coerceAtMost(duration) else position }
            playerIsPlaying = exoPlayer.isPlaying
            playerPlayWhenReady = exoPlayer.playWhenReady
            playerIsBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
            delay(if (playingCatchupProgram != null) 500L else 1_500L)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, usingOwnTvMpvFallback) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                    ownTvMpvFallback.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (usingOwnTvMpvFallback) ownTvMpvFallback.resume()
                    else if (playingChannelId != null) exoPlayer.play()
                    if (currentUiState.isConfigured &&
                        currentUiState.snapshot.channels.isNotEmpty() &&
                        viewModel.iptvRepository.cachedEpgAgeMs() > 6 * 60 * 60_000L
                    ) {
                        viewModel.refresh(force = false, showLoading = false, forceEpg = false)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var lastPreparedStreamUrl by remember { mutableStateOf<String?>(null) }
    var lastPreparedIsHls by remember { mutableStateOf(false) }
    var lastPreparedHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lastPreparedCatchupOffsetMs by remember { mutableLongStateOf(-1L) }
    var playerRetryCount by remember { mutableIntStateOf(0) }
    var retryInFlight by remember { mutableStateOf(false) }
    var lastPlaybackReadyAtMs by remember { mutableLongStateOf(0L) }
    var playbackDiagnostic by remember { mutableStateOf<PlaybackDiagnostic?>(null) }
    val mpvPlaying by ownTvMpvFallback.playing.collectAsStateWithLifecycle()
    val mpvBuffering by ownTvMpvFallback.buffering.collectAsStateWithLifecycle()
    val mpvError by ownTvMpvFallback.error.collectAsStateWithLifecycle()
    LaunchedEffect(usingOwnTvMpvFallback, mpvPlaying, mpvBuffering, mpvError) {
        if (usingOwnTvMpvFallback) {
            playerIsPlaying = mpvPlaying
            playerIsBuffering = mpvBuffering
            if (mpvPlaying) playbackDiagnostic = null
            mpvError?.let {
                playbackDiagnostic = PlaybackDiagnostic(
                    title = context.getString(R.string.live_diag_playback_failed),
                    detail = it, severity = PlaybackDiagnosticSeverity.Error)
            }
        }
    }

    fun prepareStream(
        stream: String,
        isHls: Boolean,
        headers: Map<String, String>,
        resetRetry: Boolean,
        initialPositionMs: Long = 0L,
        drmInfo: com.arflix.tv.data.model.DrmInfo? = null,
        forcePrepare: Boolean = false,
    ) {
        val mergedHeaders = (baseRequestHeaders + headers).safePlaybackHeaders()
        iptvDataSourceFactory.setDefaultRequestProperties(mergedHeaders)

        if (!forcePrepare &&
            stream == lastPreparedStreamUrl &&
            isHls == lastPreparedIsHls &&
            headers == lastPreparedHeaders &&
            (playingCatchupProgram == null || catchupUrlAnchorOffsetMs == lastPreparedCatchupOffsetMs)
        ) {
            return
        }

        // OwnTV Player Core owns live and archive channels, including its own
        // codec/reconnection ladder. Keep ARVIO's DRM adapter for schemes such
        // as PlayReady that OwnTV does not support.
        if (drmInfo == null) {
            exoPlayer.stop()
            usingOwnTvMpvFallback = true
            lastPreparedStreamUrl = stream
            lastPreparedIsHls = isHls
            lastPreparedHeaders = headers
            lastPreparedCatchupOffsetMs = if (playingCatchupProgram != null) catchupUrlAnchorOffsetMs else -1L
            playerIsBuffering = true
            playbackDiagnostic = PlaybackDiagnostic(
                title = context.getString(R.string.live_diag_starting_stream),
                detail = "OwnTV Player Core", severity = PlaybackDiagnosticSeverity.Info)
            val ownTvSourceChannel = playingChannel?.source
            val ownTvSourceProgram = playingCatchupProgram?.shiftedForCatchup(catchupUrlAnchorOffsetMs)
            ownTvMpvFallback.play(stream, mergedHeaders, archive = playingCatchupProgram != null,
                startPositionMs = initialPositionMs,
                refreshUrl = ownTvSourceChannel?.let { channel ->
                    {
                        viewModel.resolvePlayableStreamUrl(channel, ownTvSourceProgram, forceRefresh = true).url
                    }
                })
            return
        }
        if (usingOwnTvMpvFallback) {
            ownTvMpvFallback.stop()
            usingOwnTvMpvFallback = false
        }

        playerIsBuffering = true
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        val mediaItem = MediaItem.Builder()
            .setUri(stream)
            .apply {
                if (isHls) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                } else if (looksLikeMpegTsUrl(stream)) {
                    setMimeType(MimeTypes.VIDEO_MP2T)
                }
                if (playingCatchupProgram == null) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f)
                            .setTargetOffsetMs(8_000).build()
                    )
                }
                // DRM configuration from #KODIPROP directives
                drmInfo?.let { drm ->
                    val schemeUuid = com.arflix.tv.util.ClearKeyUtil.drmSchemeToUuid(drm.scheme)
                    val drmBuilder = MediaItem.DrmConfiguration.Builder(schemeUuid)
                    if (drm.scheme == "clearkey" && !drm.licenseUrl.isNullOrBlank()) {
                        // ClearKey: build inline JWKS data URI from kid:key hex pair
                        com.arflix.tv.util.ClearKeyUtil.buildClearKeyLicenseUri(drm.licenseUrl)
                            ?.let { dataUri -> drmBuilder.setLicenseUri(dataUri) }
                    } else if (!drm.licenseUrl.isNullOrBlank()) {
                        // Widevine / PlayReady: strip Kodi pipe syntax, use clean URL
                        drmBuilder.setLicenseUri(drm.licenseUrl.substringBefore("|"))
                    }
                    setDrmConfiguration(drmBuilder.build())
                }
            }
            .build()
        if (initialPositionMs > 0L) {
            exoPlayer.setMediaItem(mediaItem, initialPositionMs)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
        lastPreparedStreamUrl = stream
        lastPreparedIsHls = isHls
        lastPreparedHeaders = headers
        lastPreparedCatchupOffsetMs = if (playingCatchupProgram != null) catchupUrlAnchorOffsetMs else -1L
        if (resetRetry) {
            playerRetryCount = 0
            retryInFlight = false
            lastPlaybackReadyAtMs = 0L
        }
        if (resetRetry) {
            playbackDiagnostic = PlaybackDiagnostic(
                title = if (playingCatchupProgram != null && initialPositionMs > 0L) context.getString(R.string.live_diag_seeking_catchup) else context.getString(R.string.live_diag_starting_stream),
                detail = playingChannel?.name ?: context.getString(R.string.live_diag_preparing_source),
                severity = PlaybackDiagnosticSeverity.Info,
            )
        }
        System.err.println(
            "[IPTV-Catchup] prepare catchup=${playingCatchupProgram != null} " +
                "anchor=$catchupUrlAnchorOffsetMs inSegment=$initialPositionMs " +
                "target=$catchupPlaybackOffsetMs url=${redactPlaybackUrl(stream)}"
        )
    }

    fun toggleCatchupPlayback() {
        if (playingCatchupProgram == null) return
        if (usingOwnTvMpvFallback) {
            ownTvMpvFallback.togglePlayPause()
            hudPokeSignal++
            return
        }
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            playerPlayWhenReady = false
            System.err.println("[IPTV-Catchup] pause position=${exoPlayer.currentPosition}")
        } else {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
            playerPlayWhenReady = true
            System.err.println("[IPTV-Catchup] play position=${exoPlayer.currentPosition}")
        }
        hudPokeSignal++
    }

    fun seekCatchupBy(deltaMs: Long) {
        val program = playingCatchupProgram ?: return
        val duration = (program.endUtcMillis - program.startUtcMillis).coerceAtLeast(0L)
            .takeIf { it > 0L }
            ?: playerDurationMs
        val wasPlayRequested = exoPlayer.playWhenReady
        val maxPosition = if (duration > 1_000L) duration - 1_000L else duration
        val current = (catchupUrlAnchorOffsetMs +
            (if (usingOwnTvMpvFallback) ownTvMpvFallback.positionMs else exoPlayer.currentPosition).coerceAtLeast(0L))
            .let { if (maxPosition > 0L) it.coerceAtMost(maxPosition) else it }
        val target = (current + deltaMs)
            .coerceAtLeast(0L)
            .let { if (maxPosition > 0L) it.coerceAtMost(maxPosition) else it }
        if (target == catchupPlaybackOffsetMs) {
            hudPokeSignal++
            return
        }
        val source = playingChannel?.source
        val targetAnchor = source?.catchupUrlAnchorOffset(target) ?: 0L
        val targetInSegment = source?.catchupInSegmentSeekOffset(target) ?: target
        val sameAnchor = targetAnchor == catchupUrlAnchorOffsetMs
        catchupPlaybackOffsetMs = target
        playerPositionMs = target
        if (usingOwnTvMpvFallback) {
            if (sameAnchor) ownTvMpvFallback.seekTo(targetInSegment)
            ownTvMpvFallback.setPlaying(true)
            playerPlayWhenReady = true
            hudPokeSignal++
            return
        }
        exoPlayer.playWhenReady = true
        if (sameAnchor) {
            exoPlayer.seekTo(targetInSegment)
        }
        exoPlayer.play()
        playerPlayWhenReady = true
        System.err.println(
            "[IPTV-Catchup] seek delta=$deltaMs current=$current target=$target duration=$duration " +
                "wasPlayRequested=$wasPlayRequested state=${exoPlayer.playbackState} " +
                "anchor=$catchupUrlAnchorOffsetMs targetAnchor=$targetAnchor " +
                "inSegment=$targetInSegment sameAnchor=$sameAnchor exo=${exoPlayer.currentPosition}"
        )
        hudPokeSignal++
    }

    fun seekToPosition(targetMs: Long) {
        if (playingCatchupProgram != null) {
            val delta = targetMs - playerPositionMs
            seekCatchupBy(delta)
        } else {
            val currentNow = currentNowNext?.now
            val ch = playingChannel
            val currentElapsed = if (currentNow != null && currentNow.startUtcMillis > 0L) {
                (System.currentTimeMillis() - currentNow.startUtcMillis).coerceAtLeast(0L)
            } else {
                playerPositionMs
            }
            val boundedTarget = targetMs.coerceIn(0L, currentElapsed)
            if (boundedTarget >= currentElapsed) {
                hudPokeSignal++
                return
            }
            if (ch != null && currentNow != null && ch.supportsCatchupHistory()) {
                System.err.println("[IPTV-Catchup] auto-switch catchup program=${currentNow.title} targetMs=$boundedTarget")
                playingCatchupProgram = currentNow
                catchupPlaybackOffsetMs = boundedTarget
                playerPositionMs = boundedTarget
                lastPreparedStreamUrl = null
                playerIsBuffering = true
                hudPokeSignal++
            } else {
                val currentExo = exoPlayer.currentPosition
                val maxExo = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 60_000L
                val delta = boundedTarget - currentElapsed
                val newExo = (currentExo + delta).coerceIn(0L, maxExo)
                exoPlayer.seekTo(newExo)
                hudPokeSignal++
            }
        }
    }

    fun returnCatchupToLive() {
        if (playingCatchupProgram == null) return
        System.err.println("[IPTV-Catchup] return-live channel=${playingChannelId.orEmpty()}")
        playingCatchupProgram = null
        catchupPlaybackOffsetMs = 0L
        fullscreenGuideOpen = false
        lastPreparedStreamUrl = null
        playerIsBuffering = true
        exoPlayer.play()
        hudPokeSignal++
    }

    // When the selected channel changes, swap media item.
    val currentStreamUrl = remember(playingChannel, playingCatchupProgram, catchupUrlAnchorOffsetMs) {
        val ch = playingChannel ?: return@remember initialStreamUrl
        val pr = playingCatchupProgram
        if (pr != null) {
            viewModel.iptvRepository.getCatchupUrl(ch.source, pr.shiftedForCatchup(catchupUrlAnchorOffsetMs))
        } else {
            ch.streamUrl
        }
    }
    val openFullScreenPlayer = remember(playingChannelId, currentStreamUrl) {
        {
            if (playingChannelId != null || currentStreamUrl != null) {
                isFullScreen = true
                hudPokeSignal++
            }
        }
    }
    LaunchedEffect(currentStreamUrl, playingCatchupProgram, catchupUrlAnchorOffsetMs, playingChannel?.id) {
        // Debounce: rapid channel changes (holding channel-down, fast zapping)
        // previously rebuilt the native decoder — exoPlayer.stop() -> clearMediaItems()
        // -> setMediaItem() -> prepare() — on every single intermediate channel with
        // no throttling at all. Native decoder teardown isn't instant, so quick
        // repeated changes could leave more than one codec instance alive in native
        // memory at once, which is a well-known way to exhaust memory and crash
        // natively on low-RAM TV hardware. LaunchedEffect cancels this coroutine
        // outright when its keys change again, so this delay is a real, clean
        // cancellation point — only the channel the user actually settles on for
        // longer than the debounce window ever reaches the expensive resolve+prepare
        // work below; every skipped-through channel in between costs nothing.
        delay(250L)
        val rawStream = currentStreamUrl ?: return@LaunchedEffect
        val sourceChannel = playingChannel?.source
        val streamProgram = playingCatchupProgram?.shiftedForCatchup(catchupUrlAnchorOffsetMs)
        val target = runCatching {
            if (sourceChannel != null) {
                viewModel.resolvePlayableStreamUrl(sourceChannel, streamProgram, catchupAttempt = 0)
            } else {
                IptvPlaybackTarget(rawStream)
            }
        }.getOrElse { error ->
            playbackDiagnostic = PlaybackDiagnostic(
                title = if (playingCatchupProgram != null) context.getString(R.string.live_diag_catchup_unavailable) else context.getString(R.string.live_diag_playback_failed),
                detail = error.message ?: context.getString(R.string.live_diag_no_playable_stream),
                severity = PlaybackDiagnosticSeverity.Error,
            )
            System.err.println(
                "[IPTV] Failed to resolve playable stream catchup=${playingCatchupProgram != null} " +
                    "channel=${sourceChannel?.id.orEmpty()} reason=${error.message}"
            )
            return@LaunchedEffect
        }
        val headers = sourceChannel?.requestHeaders.orEmpty()
        val initialSeekMs = if (playingCatchupProgram != null) catchupInSegmentSeekMs else 0L

        prepareStream(
            stream = target.url,
            isHls = target.isHls,
            headers = headers,
            resetRetry = true,
            initialPositionMs = initialSeekMs,
            drmInfo = playingChannel?.source?.drmInfo,
        )
        // Persist "recent" as soon as playback starts.
        playingChannelId?.let { id ->
            val set = LinkedHashSet(recents.value)
            set.remove(id); set.add(id)
            while (set.size > 40) set.remove(set.first())
            recents.value = set
            viewModel.rememberTvSession(
                lastChannelId = id,
                lastGroupName = selectedCategoryId,
                lastFocusedZone = "GUIDE",
                markOpened = true,
            )
        }
    }

    DisposableEffect(
        exoPlayer,
        lastPreparedStreamUrl,
        lastPreparedIsHls,
        lastPreparedHeaders,
        playingChannel?.id,
        playingCatchupProgram,
        catchupPlaybackOffsetMs,
        usingOwnTvMpvFallback
    ) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (usingOwnTvMpvFallback) return
                playerIsBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    playbackDiagnostic = null
                    playerIsBuffering = false
                    lastPlaybackReadyAtMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (usingOwnTvMpvFallback) return
                if (exoPlayer.playbackState == Player.STATE_BUFFERING || (isLoading && !exoPlayer.isPlaying)) {
                    playerIsBuffering = true
                } else if (exoPlayer.playbackState == Player.STATE_READY) {
                    playerIsBuffering = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (usingOwnTvMpvFallback) return
                playerIsBuffering = false
                val prepared = lastPreparedStreamUrl ?: return
                val preparedIsHls = lastPreparedIsHls
                // ExoPlayer can report more than one error callback for the same
                // failed preparation. Never let those callbacks queue parallel
                // decoder teardowns/rebuilds; that is especially destructive on
                // low-memory TV hardware and makes an otherwise playing stream
                // stutter until the old six-retry loop eventually freezes it.
                if (retryInFlight) return
                val now = SystemClock.elapsedRealtime()
                if (lastPlaybackReadyAtMs > 0L && now - lastPlaybackReadyAtMs >= 15_000L) {
                    // A stream that ran cleanly for a while gets one fresh recovery
                    // opportunity for a later network interruption.
                    playerRetryCount = 0
                }
                val nextAttempt = playerRetryCount + 1
                playerRetryCount = nextAttempt
                val retryChannel = playingChannel?.source
                val retryProgram = playingCatchupProgram
                val retryStreamProgram = retryProgram?.shiftedForCatchup(catchupUrlAnchorOffsetMs)
                val catchupCandidateCount = if (retryChannel != null && retryProgram != null) {
                    viewModel.iptvRepository.getCatchupUrlCandidates(
                        retryChannel,
                        retryStreamProgram ?: retryProgram
                    ).size
                } else {
                    0
                }
                val maxRetryCount = if (retryProgram != null) {
                    (catchupCandidateCount - 1).coerceAtLeast(0).coerceAtMost(2)
                } else {
                    // Live sources receive a single controlled recovery. Repeated
                    // full decoder rebuilds made healthy streams choppy and then
                    // left the player frozen after six failed attempts.
                    1
                }
                if (nextAttempt > maxRetryCount) {
                    if (!usingOwnTvMpvFallback && retryProgram == null &&
                        retryChannel?.drmInfo == null && ownTvMpvFallback.available) {
                        // OwnTV's FFmpeg/mpv engine accepts a number of raw TS,
                        // codec and provider-header combinations that Media3
                        // cannot. Only hand over after the bounded Media3 retry
                        // has failed, avoiding duplicate provider sessions for
                        // healthy channels.
                        usingOwnTvMpvFallback = true
                        exoPlayer.stop()
                        ownTvMpvFallback.play(prepared, retryChannel?.requestHeaders ?: lastPreparedHeaders)
                        playbackDiagnostic = PlaybackDiagnostic(
                            title = context.getString(R.string.live_diag_retrying_source),
                            detail = "Switching to OwnTV compatibility player",
                            severity = PlaybackDiagnosticSeverity.Warning,
                        )
                        return
                    }
                    playbackDiagnostic = PlaybackDiagnostic(
                        title = context.getString(R.string.live_diag_playback_failed),
                        detail = "${error.errorCodeName}: ${classifyPlaybackError(error)}",
                        severity = PlaybackDiagnosticSeverity.Error,
                    )
                    System.err.println(
                        "[IPTV] Live playback failed after retries code=${error.errorCode} " +
                            "name=${error.errorCodeName} status=${httpResponseCode(error) ?: "-"} " +
                            "attempts=$maxRetryCount candidates=$catchupCandidateCount " +
                            "url=${redactPlaybackUrl(prepared)}"
                    )
                    return
                }
                val retryHeaders = retryChannel?.requestHeaders ?: lastPreparedHeaders
                retryInFlight = true
                coroutineScope.launch {
                    delay(350L * nextAttempt)
                    val retryTarget = runCatching {
                        if (retryChannel != null) {
                            viewModel.resolvePlayableStreamUrl(
                                channel = retryChannel,
                                program = retryStreamProgram ?: retryProgram,
                                forceRefresh = true,
                                catchupAttempt = if (retryProgram != null) nextAttempt else 0
                            )
                        } else {
                            IptvPlaybackTarget(prepared, preparedIsHls)
                        }
                    }.getOrElse { resolveError ->
                        playbackDiagnostic = PlaybackDiagnostic(
                            title = if (retryProgram != null) context.getString(R.string.live_diag_catchup_unavailable) else context.getString(R.string.live_diag_playback_failed),
                            detail = resolveError.message ?: classifyPlaybackError(error),
                            severity = PlaybackDiagnosticSeverity.Error,
                        )
                        System.err.println(
                            "[IPTV] Retry resolve failed catchup=${retryProgram != null} " +
                                "code=${error.errorCodeName} reason=${resolveError.message}"
                        )
                        return@launch
                    }
                    System.err.println(
                        "[IPTV] Retrying live playback attempt=$nextAttempt " +
                            "code=${error.errorCodeName} status=${httpResponseCode(error) ?: "-"} " +
                            "candidates=$catchupCandidateCount url=${redactPlaybackUrl(retryTarget.url)}"
                    )
                    playbackDiagnostic = PlaybackDiagnostic(
                        title = context.getString(R.string.live_diag_retrying_source),
                        detail = "Attempt $nextAttempt/$maxRetryCount after ${classifyPlaybackError(error)}",
                        severity = PlaybackDiagnosticSeverity.Warning,
                    )
                    retryInFlight = false
                    prepareStream(
                        stream = retryTarget.url,
                        isHls = retryTarget.isHls,
                        headers = retryHeaders,
                        resetRetry = false,
                        initialPositionMs = retryChannel?.catchupInSegmentSeekOffset(catchupPlaybackOffsetMs) ?: 0L,
                        drmInfo = retryChannel?.drmInfo,
                        forcePrepare = true,
                    )
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Default IPTV entry is the playlist/category rail. It used to land on the
    // Search row, which is what users reported: the selector opened inside the
    // search box and — because "down" from search selects the first category,
    // which does not exist until the playlist has parsed — stayed stuck there
    // through the whole load. Land on the categories instead; search is one
    // press up from there.
    LaunchedEffect(visibleEnrichedState.value !== EnrichedChannels.Empty) {
        val entry = LiveTvStartup.entryFocus(
            isTouchDevice = isTouchDevice,
            hasChannels = visibleEnrichedState.value !== EnrichedChannels.Empty,
        )
        if (entry == LiveTvStartup.EntryFocus.CATEGORY_LIST) {
            noteGuideUserNavigation()
            focusZone = LiveTvFocusZone.CATEGORY_LIST
            focusCategoryRailSignal += 1
        }
    }

    LaunchedEffect(state.isConfigured, visibleEnrichedState.value) {
        if (!isTouchDevice && !state.isConfigured && visibleEnrichedState.value === EnrichedChannels.Empty) {
            delay(100L)
            runCatching { emptyStateButtonFocus.requestFocus() }
        }
    }

    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = !searchOpen && variantPickerChannel != null) { variantPickerChannel = null }
    BackHandler(enabled = !searchOpen && isFullScreen && fullscreenGuideOpen) {
        fullscreenGuideOpen = false
    }
    BackHandler(enabled = !searchOpen && isFullScreen && !fullscreenGuideOpen) {
        if (playingCatchupProgram != null) {
            returnCatchupToLive()
        } else {
            exitFullScreenPlayback()
        }
    }
    BackHandler(enabled = !searchOpen && variantPickerChannel == null && !isFullScreen) {
        onBack()
    }

    val channelNumberExactName = remember(channelNumberBuffer, visibleChannels) {
        visibleChannels.firstOrNull { it.number.toString() == channelNumberBuffer }?.name
    }
    val channelNumberMatchCount = remember(channelNumberBuffer, visibleChannels) {
        if (channelNumberBuffer.isBlank()) {
            0
        } else {
            visibleChannels.count { it.number.toString().startsWith(channelNumberBuffer) }
        }
    }

    // Keep the ExoPlayer attached to exactly one PlayerView while the
    // fullscreen layer animates in and out. Ownership follows the logical
    // fullscreen state rather than the animation progress: when exiting,
    // switch to the newly composed mini view *before* the outgoing fullscreen
    // TextureView can be disposed. Waiting for progress to reach zero races
    // SurfaceTexture teardown on several Android TV devices and leaves the
    // decoder stuck on the removed fullscreen surface.
    val fsProgress by animateFloatAsState(
        targetValue = if (isFullScreen) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "tv-fullscreen-progress",
    )
    var miniPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var fullPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    var attachedPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    fun releasePlayerView(view: PlayerView, isMini: Boolean) {
        ownTvMpvFallback.detach(view)
        if (isMini) {
            if (miniPlayerView === view) miniPlayerView = null
        } else if (fullPlayerView === view) {
            fullPlayerView = null
        }
        // The view is being removed, so it must not be used as the old target
        // on the next handoff. Leave its stale Player reference alone: calling
        // setPlayer(null) after the replacement view is attached would clear
        // the replacement surface too.
        if (attachedPlayerView === view) attachedPlayerView = null
    }

    LaunchedEffect(
        exoPlayer,
        isFullScreen,
        miniPlayerView,
        fullPlayerView,
        usingOwnTvMpvFallback,
    ) {
        val targetView = if (isFullScreen) fullPlayerView else miniPlayerView
        // The destination AndroidView is composed in the same frame as the
        // state transition. Do not detach the current surface during that
        // short window; wait until there is a real replacement target.
        if (targetView == null) return@LaunchedEffect
        val previousView = attachedPlayerView
        if (usingOwnTvMpvFallback) {
            previousView?.player = null
            targetView.player = null
            attachedPlayerView = targetView
            ownTvMpvFallback.attach(targetView)
            return@LaunchedEffect
        }
        ownTvMpvFallback.detach(previousView)
        if (targetView.player !== exoPlayer) targetView.player = exoPlayer
        if (previousView === targetView) return@LaunchedEffect

        // Media3 explicitly recommends attaching the new target before
        // detaching the old one. This avoids a decoder/surface gap and makes
        // the reverse fullscreen transition safe on TV Surface/TextureView.
        PlayerView.switchTargetView(exoPlayer, previousView, targetView)
        attachedPlayerView = targetView
        if (exoPlayer.playWhenReady) {
            exoPlayer.play()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
            .then(
                if (!isTouchDevice) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (!searchOpen && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            digitForTvKeyCode(event.nativeKeyEvent.keyCode)?.let { digit ->
                                return@onPreviewKeyEvent handleChannelNumberDigit(digit)
                            }
                        }
                        if (searchOpen || isFullScreen || event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        noteGuideUserNavigation()
                        when (focusZone) {
                            LiveTvFocusZone.TOPBAR -> {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (topBarFocusIndex > 0) {
                                            topBarFocusIndex = (topBarFocusIndex - 1).coerceIn(0, maxTopBarIndex)
                                        }
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (topBarFocusIndex < maxTopBarIndex) {
                                            topBarFocusIndex = (topBarFocusIndex + 1).coerceIn(0, maxTopBarIndex)
                                        }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (!state.isConfigured && state.snapshot.channels.isEmpty()) {
                                            focusZone = LiveTvFocusZone.CATEGORY_LIST
                                            runCatching { emptyStateButtonFocus.requestFocus() }
                                        } else {
                                            focusProviderSwitcher()
                                        }
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (hasProfile && topBarFocusIndex == 0) {
                                            onSwitchProfile()
                                        } else {
                                            when (topBarFocusedItem(topBarFocusIndex, hasProfile)) {
                                                SidebarItem.SEARCH -> onNavigateToSearch()
                                                SidebarItem.HOME -> onNavigateToHome()
                                                SidebarItem.WATCHLIST -> onNavigateToWatchlist()
                                                SidebarItem.TV -> Unit
                                                SidebarItem.SETTINGS -> onNavigateToSettings()
                                                null -> Unit
                                            }
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            LiveTvFocusZone.PROVIDER_SWITCHER -> false
                            LiveTvFocusZone.CATEGORY_LIST -> false
                            LiveTvFocusZone.CHANNEL_LIST -> false
                            LiveTvFocusZone.EPG -> false
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Content area starts below the translucent top bar so it doesn't get
        // overwritten.
        if (isFullScreen) {
            // Full-screen playback only — no grid rendered so the single
            // PlayerView owns ExoPlayer.
        } else if (!state.isConfigured && state.snapshot.channels.isEmpty()) {
            EmptyStatePane(
                message = stringResource(R.string.live_empty_no_playlist),
                actionLabel = stringResource(R.string.live_btn_open_settings),
                onAction = onNavigateToIptvSettings ?: onNavigateToSettings,
                isFocused = focusZone != LiveTvFocusZone.TOPBAR,
                focusRequester = emptyStateButtonFocus,
                onMoveUp = {
                    focusZone = LiveTvFocusZone.TOPBAR
                    topBarFocusIndex = topBarSelectedIndex(SidebarItem.TV, hasProfile).coerceIn(0, maxTopBarIndex)
                }
            )
        } else {
            // Content starts right under the pill row — 52 dp puts the first
            // row/search field 4 dp below the pills. The remaining top-bar
            // gradient tail is transparent enough to vanish over our near-
            // black Bg so the two regions read as one surface.
            // Content sits under the top bar (82dp tall with a dark-to-
            // transparent gradient). Starting at 0dp lets the grid/sidebar
            // background bleed up into the transparent tail of the gradient
            // so the two regions read as one surface instead of a hovering
            // chip row. The content itself gets an internal top padding so
            // nothing important renders under the opaque chips.
            if (useTouchRail) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                ) {
                    if (playlistCategorySections.isEmpty()) {
                        ProviderSelector(
                            providers = providerFilters,
                            selectedId = selectedProviderId,
                            onSelect = { id ->
                                noteGuideUserNavigation()
                                selectedProviderId = id
                                selectedCategoryId = "all"
                                focusedChannelId = null
                                epgPrefetchAnchorId = null
                            },
                            onMoveDown = { focusPlaylistSearch() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MiniPlayerRow(
                        channel = playingChannel,
                        clockTickMillis = guideClockMillis,
                        nowNext = currentNowNext,
                        onFavoriteToggle = { viewModel.toggleFavoriteChannel(it) },
                        favoriteSet = favSet,
                        onFullscreenClick = openFullScreenPlayer,
                        variantCount = playingChannel?.let { variantCountFor(it, variantGroups) } ?: 1,
                        onOpenVariants = playingChannel?.let { channel -> { openVariantPicker(channel) } },
                        compact = true,
                        landscapeCompact = landscapeCompactMiniPlayer,
                        onPlayerViewCreated = { view -> miniPlayerView = view },
                        onPlayerViewReleased = { view -> releasePlayerView(view, isMini = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TouchCategoryRail(
                        tree = visibleEnrichedState.value.tree,
                        selectedId = selectedCategoryId,
                        playlistSections = playlistCategorySections,
                        onSelect = { id ->
                            noteGuideUserNavigation()
                            selectedCategoryId = id
                        },
                        onOpenSearch = { searchOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GuideBackfillStatus(
                        inProgress = visibleGuideLoadInProgress,
                        percent = state.epgBackfillProgress,
                        message = visibleGuideLoadMessage,
                    )
                    TvGuideViewport(
                        channels = guideChannels,
                        channelWindowOffset = normalizedGuideStart,
                        totalChannelCount = selectedCategoryTotalCount,
                        clockTickMillis = guideClockMillis,
                        indexedPrograms = retainedIndexedGuidePrograms,
                        fallbackNowNext = state.snapshot.nowNext,
                        channelNowNext = effectiveGuideNowNext,
                        epgLoadingChannelIds = state.epgLoadingChannelIds,
                        epgAttemptedChannelIds = state.epgAttemptedChannelIds,
                        isGuideBackfillLoading = visibleGuideLoadInProgress,
                        hasGuideSource = state.hasPotentialGuideSource,
                        selectedChannelId = selectedDisplayChannelId,
                        focusSelectedChannelSignal = focusSelectedChannelSignal,
                        focusEpgSignal = focusEpgSignal,
                        focusMode = if (focusZone == LiveTvFocusZone.EPG) {
                            EpgGridFocusMode.Epg
                        } else {
                            EpgGridFocusMode.ChannelList
                        },
                        scrollResetKey = "$selectedProviderId|$selectedCategoryId|$filteredChannelsWindowKey|$normalizedGuideStart",
                        compact = true,
                        gridFocused = focusZone == LiveTvFocusZone.EPG,
                        onChannelSelect = { channel ->
                            focusZone = LiveTvFocusZone.CHANNEL_LIST
                            val currentProgram = displayedCurrentProgram(channel)
                            selectChannel(channel, currentProgram)
                        },
                        onProgramSelect = { channel, program ->
                            program?.let { selectEpgProgram(channel, it) }
                        },
                        onChannelFocused = { channel -> commitFocusedChannel(channel) },
                        onChannelFavoriteToggle = { id -> viewModel.toggleFavoriteChannel(id) },
                        favorites = favSet,
                        variantCountFor = { channel -> variantCountFor(channel, variantGroups) },
                        onOpenVariants = { channel -> openVariantPicker(channel) },
                        onMoveLeftFromChannels = { focusSelectedCategory() },
                        onEnterEpg = { channel -> focusEpg(channel.id) },
                        onExitEpg = { channel -> focusChannelList(channel?.id ?: focusedChannelId ?: playingChannelId) },
                        onRequestPreviousChannels = ::requestGuideWindowBefore,
                        onRequestNextChannels = ::requestGuideWindowAfter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                CategorySidebar(
                    tree = visibleEnrichedState.value.tree,
                    selectedId = selectedCategoryId,
                    playlistSections = playlistCategorySections,
                    expanded = sidebarExpanded,
                    listState = sidebarListState,
                    focusRequester = sidebarFocus,
                    onSelect = { id ->
                        noteGuideUserNavigation()
                        selectedCategoryId = id
                    },
                    onOpenSearch = { searchOpen = true },
                    onHideCategory = { playlistId, groupName ->
                        noteGuideUserNavigation()
                        selectedCategoryId = "all"
                        viewModel.toggleHiddenGroup(playlistId, groupName)
                    },
                    onUnhideCategory = { playlistId, groupName ->
                        noteGuideUserNavigation()
                        viewModel.toggleHiddenGroup(playlistId, groupName)
                    },
                    onMoveCategoryUp = { playlistId, groupName ->
                        viewModel.moveGroupUp(playlistId, groupName)
                    },
                    onMoveCategoryToTop = { playlistId, groupName ->
                        viewModel.moveGroupToTop(playlistId, groupName)
                    },
                    onMoveCategoryDown = { playlistId, groupName ->
                        viewModel.moveGroupDown(playlistId, groupName)
                    },
                    onFocusEnter = {
                        if (focusZone != LiveTvFocusZone.TOPBAR) {
                            focusZone = LiveTvFocusZone.CATEGORY_LIST
                        }
                    },
                    onMoveRight = {
                        val remembered = rememberedChannelByCategory[selectedCategoryId]
                            ?.takeIf { id -> id in filteredChannelIndexById }
                        val target = remembered
                            ?: focusedChannelId?.takeIf { id -> id in filteredChannelIndexById }
                            ?: playingChannelId?.takeIf { id -> id in filteredChannelIndexById }
                            ?: filteredChannels.firstOrNull()?.id
                        focusChannelList(target)
                    },
                    onMoveUpFromSearch = {
                        topBarFocusIndex = topBarSelectedIndex(SidebarItem.TV, hasProfile)
                            .coerceIn(0, maxTopBarIndex)
                        focusZone = LiveTvFocusZone.TOPBAR
                    },
                    focusSearchSignal = focusSearchCategorySignal,
                    focusCategorySignal = focusCategoryRailSignal,
                    isTouchDevice = isTouchDevice,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = contentTopPadding)
                        .focusGroup(),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                ) {
                    if (playlistCategorySections.isEmpty()) {
                        ProviderSelector(
                            providers = providerFilters,
                            selectedId = selectedProviderId,
                            onSelect = { id ->
                                noteGuideUserNavigation()
                                selectedProviderId = id
                                selectedCategoryId = "all"
                                focusedChannelId = null
                                epgPrefetchAnchorId = null
                            },
                            focusRequester = providerFocus,
                            onMoveUp = {
                                topBarFocusIndex = topBarSelectedIndex(SidebarItem.TV, hasProfile)
                                    .coerceIn(0, maxTopBarIndex)
                                focusZone = LiveTvFocusZone.TOPBAR
                            },
                            onMoveDown = { focusPlaylistSearch() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MiniPlayerRow(
                        channel = playingChannel,
                        clockTickMillis = guideClockMillis,
                        nowNext = currentNowNext,
                        onFavoriteToggle = { viewModel.toggleFavoriteChannel(it) },
                        favoriteSet = favSet,
                        onFullscreenClick = openFullScreenPlayer,
                        variantCount = playingChannel?.let { variantCountFor(it, variantGroups) } ?: 1,
                        onOpenVariants = playingChannel?.let { channel -> { openVariantPicker(channel) } },
                        compact = compactTouchLayout,
                        onPlayerViewCreated = { view -> miniPlayerView = view },
                        onPlayerViewReleased = { view -> releasePlayerView(view, isMini = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GuideBackfillStatus(
                        inProgress = visibleGuideLoadInProgress,
                        percent = state.epgBackfillProgress,
                        message = visibleGuideLoadMessage,
                    )
                    TvGuideViewport(
                        channels = guideChannels,
                        channelWindowOffset = normalizedGuideStart,
                        totalChannelCount = selectedCategoryTotalCount,
                        clockTickMillis = guideClockMillis,
                        indexedPrograms = retainedIndexedGuidePrograms,
                        fallbackNowNext = state.snapshot.nowNext,
                        channelNowNext = effectiveGuideNowNext,
                        epgLoadingChannelIds = state.epgLoadingChannelIds,
                        epgAttemptedChannelIds = state.epgAttemptedChannelIds,
                        isGuideBackfillLoading = visibleGuideLoadInProgress,
                        hasGuideSource = state.hasPotentialGuideSource,
                        selectedChannelId = selectedDisplayChannelId,
                        focusSelectedChannelSignal = focusSelectedChannelSignal,
                        focusEpgSignal = focusEpgSignal,
                        focusMode = if (focusZone == LiveTvFocusZone.EPG) {
                            EpgGridFocusMode.Epg
                        } else {
                            EpgGridFocusMode.ChannelList
                        },
                        scrollResetKey = "$selectedProviderId|$selectedCategoryId|$filteredChannelsWindowKey|$normalizedGuideStart",
                        compact = compactTouchLayout,
                        gridFocused = focusZone == LiveTvFocusZone.CHANNEL_LIST || focusZone == LiveTvFocusZone.EPG,
                        onChannelSelect = { channel ->
                            val currentProgram = displayedCurrentProgram(channel)
                            selectChannel(channel, currentProgram)
                        },
                        onProgramSelect = { channel, program ->
                            program?.let { selectEpgProgram(channel, it) }
                        },
                        onChannelFocused = { channel -> commitFocusedChannel(channel) },
                        onChannelFavoriteToggle = { id -> viewModel.toggleFavoriteChannel(id) },
                        favorites = favSet,
                        variantCountFor = { channel -> variantCountFor(channel, variantGroups) },
                        onOpenVariants = { channel -> openVariantPicker(channel) },
                        onMoveLeftFromChannels = { focusSelectedCategory() },
                        onEnterEpg = { channel -> focusEpg(channel.id) },
                        onExitEpg = { channel -> focusChannelList(channel?.id ?: focusedChannelId ?: playingChannelId) },
                        onRequestPreviousChannels = ::requestGuideWindowBefore,
                        onRequestNextChannels = ::requestGuideWindowAfter,
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged {
                                if (it.hasFocus && focusZone == LiveTvFocusZone.CATEGORY_LIST) {
                                    focusZone = LiveTvFocusZone.CHANNEL_LIST
                                }
                            }
                            .then(if (!isTouchDevice) Modifier.focusRequester(epgFocus) else Modifier),
                    )
                }
            }
        }

        // Full-screen playback: same ExoPlayer, covers the entire screen.
        //
        // The overlay animates a scale+alpha transition so it looks like the
        // mini-player is growing into fullscreen. The transform pivot is
        // roughly the mini-player's center (sidebar ≈ 20% of width, mini-
        // player sits just below the 52dp top bar), which keeps the grow
        // anchored visually to where the user tapped instead of from screen
        // center. fsProgress stays mounted until it reaches 0, so the
        // reverse animation also plays on Back.
        if (fsProgress > 0f && playingChannel != null) {
            val scale = 0.35f + 0.65f * fsProgress
    BackHandler(enabled = isFullScreen) {
        if (fullscreenGuideOpen) {
            fullscreenGuideOpen = false
            hudPokeSignal++
        } else if (!quickZapOpen) {
            if (playingCatchupProgram != null) {
                returnCatchupToLive()
            } else {
                exitFullScreenPlayback()
            }
        }
    }

    Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(
                            pivotFractionX = 0.22f,
                            pivotFractionY = 0.18f,
                        )
                        scaleX = scale
                        scaleY = scale
                        alpha = fsProgress
                    }
                    .background(Color.Black)
                    .focusRequester(fsFocus)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (!isFullScreen || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (fullscreenGuideOpen) {
                            when (ev.key) {
                                Key.Back, Key.Escape -> {
                                    fullscreenGuideOpen = false
                                    hudPokeSignal++
                                    true
                                }
                                else -> false
                            }
                        } else if (quickZapOpen) {
                            false
                        } else {
                            val firstPress = ev.nativeKeyEvent.repeatCount == 0
                            if (firstPress && playingCatchupProgram != null) {
                                when (ev.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                    AndroidKeyEvent.KEYCODE_SPACE -> {
                                        toggleCatchupPlayback()
                                        return@onPreviewKeyEvent true
                                    }
                                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                                        if (usingOwnTvMpvFallback) ownTvMpvFallback.setPlaying(true) else exoPlayer.play()
                                        hudPokeSignal++
                                        return@onPreviewKeyEvent true
                                    }
                                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                        if (usingOwnTvMpvFallback) ownTvMpvFallback.setPlaying(false) else exoPlayer.pause()
                                        hudPokeSignal++
                                        return@onPreviewKeyEvent true
                                    }
                                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                        seekCatchupBy(-CatchupSeekStepMs)
                                        return@onPreviewKeyEvent true
                                    }
                                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                        seekCatchupBy(CatchupSeekStepMs)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            if (firstPress) {
                                digitForTvKeyCode(ev.nativeKeyEvent.keyCode)?.let { digit ->
                                    hudPokeSignal++
                                    return@onPreviewKeyEvent handleChannelNumberDigit(digit)
                                }
                                if (!isHudVisible) {
                                    if (ev.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)) {
                                        hudPokeSignal++
                                        return@onPreviewKeyEvent true
                                    }
                                }
                            }
                            when (ev.key) {
                                Key.Back, Key.Escape -> {
                                    if (firstPress) {
                                        if (playingCatchupProgram != null) {
                                            returnCatchupToLive()
                                        } else {
                                            exitFullScreenPlayback()
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                    .then(
                        if (isTouchDevice) {
                            Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) {
                                hudPokeSignal++
                            }
                        } else if (isFullScreen && !fullscreenGuideOpen && !quickZapOpen) {
                            Modifier.onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown) {
                                    hudPokeSignal++
                                }
                                false
                            }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        val view = (LayoutInflater.from(ctx)
                            .inflate(R.layout.live_tv_player_view, null) as PlayerView).apply {
                            keepScreenOn = true
                            useController = false
                            setKeepContentOnPlayerReset(true)
                        }
                        fullPlayerView = view
                        view
                    },
                    update = { view ->
                        view.keepScreenOn = true
                        fullPlayerView = view
                    },
                    onRelease = { view ->
                        releasePlayerView(view, isMini = false)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isFullScreen && !fullscreenGuideOpen && !quickZapOpen) {
                    val categoryTitle = playingChannel?.source?.group?.takeIf { it.isNotBlank() }
                        ?: visibleEnrichedState.value.tree.byId(selectedCategoryId)?.label
                        ?: selectedCategoryId

                    FullscreenHud(
                        channel = playingChannel,
                        nowNext = currentNowNext,
                        pokeSignal = hudPokeSignal,
                        categoryName = categoryTitle,
                        isCatchupMode = playingCatchupProgram != null,
                        isPlaying = if (playingCatchupProgram != null) playerPlayWhenReady else playerIsPlaying,
                        isBuffering = playerIsBuffering,
                        playbackPositionMs = playerPositionMs,
                        playbackDurationMs = playerDurationMs,
                        onBackClick = if (isTouchDevice) {
                            {
                                if (playingCatchupProgram != null) {
                                    returnCatchupToLive()
                                } else {
                                    exitFullScreenPlayback()
                                }
                            }
                        } else {
                            null
                        },
                        onGuideClick = { openFullscreenGuide() },
                        onPlayPauseClick = {
                            if (usingOwnTvMpvFallback) {
                                ownTvMpvFallback.togglePlayPause()
                                hudPokeSignal++
                            } else if (playingCatchupProgram != null) {
                                toggleCatchupPlayback()
                            } else {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                    playerPlayWhenReady = false
                                } else {
                                    exoPlayer.playWhenReady = true
                                    exoPlayer.play()
                                    playerPlayWhenReady = true
                                }
                                hudPokeSignal++
                            }
                        },
                        onRewindClick = {
                            val currentNow = currentNowNext?.now
                            val currentElapsed = if (currentNow != null && currentNow.startUtcMillis > 0L) {
                                (System.currentTimeMillis() - currentNow.startUtcMillis).coerceAtLeast(0L)
                            } else {
                                playerPositionMs
                            }
                            seekToPosition((currentElapsed - 10_000L).coerceAtLeast(0L))
                        },
                        onFastForwardClick = {
                            val currentNow = currentNowNext?.now
                            val currentElapsed = if (currentNow != null && currentNow.startUtcMillis > 0L) {
                                (System.currentTimeMillis() - currentNow.startUtcMillis).coerceAtLeast(0L)
                            } else {
                                playerPositionMs
                            }
                            seekToPosition(currentElapsed + 10_000L)
                        },
                        onPreviousCatchupClick = {
                            val curIdx = filteredChannels.indexOfFirst { it.id == playingChannel?.id }
                            if (curIdx > 0) {
                                selectChannel(filteredChannels[curIdx - 1])
                            }
                        },
                        onNextCatchupClick = {
                            val curIdx = filteredChannels.indexOfFirst { it.id == playingChannel?.id }
                            if (curIdx in 0 until filteredChannels.size - 1) {
                                selectChannel(filteredChannels[curIdx + 1])
                            }
                        },
                        onReplayClick = {
                            if (playingCatchupProgram != null) {
                                seekCatchupBy(-playerPositionMs)
                            } else {
                                val preparedStream = lastPreparedStreamUrl
                                if (preparedStream != null) {
                                    prepareStream(
                                        stream = preparedStream,
                                        isHls = lastPreparedIsHls,
                                        headers = lastPreparedHeaders,
                                        resetRetry = true,
                                        drmInfo = playingChannel?.source?.drmInfo,
                                        forcePrepare = true,
                                    )
                                }
                                hudPokeSignal++
                            }
                        },
                        onGoLiveClick = { returnCatchupToLive() },
                        onSeekToPosition = { targetMs ->
                            seekToPosition(targetMs)
                        },
                        onOpenQuickZap = {
                            quickZapOpen = true
                            isHudVisible = false
                        },
                        onVisibilityChanged = { isHudVisible = it },
                        modifier = Modifier,
                    )
                }
                FullscreenGuideOverlay(
                    visible = isFullScreen && fullscreenGuideOpen,
                    channel = guideChannel ?: playingChannel,
                    guide = guideForChannel(guideChannel ?: playingChannel),
                    selectedProgram = playingCatchupProgram,
                    clockTickMillis = guideClockMillis,
                    isTouchDevice = isTouchDevice,
                    onDismiss = {
                        fullscreenGuideOpen = false
                        if (guideOpenedFromQuickZap) {
                            guideOpenedFromQuickZap = false
                            quickZapOpen = true
                        } else {
                            hudPokeSignal++
                        }
                    },
                    onProgramSelect = { program ->
                        val target = guideChannel ?: playingChannel
                        guideOpenedFromQuickZap = false
                        if (program != null && target != null) {
                            when {
                                program.endUtcMillis <= guideClockMillis ->
                                    playProgramInFullscreen(program, target)
                                program.isLive(guideClockMillis) && isSamePlayingChannel(target) && state.epgVodActionsEnabled ->
                                    resolveVodOrPlayFullscreen(target, program)
                                program.isLive(guideClockMillis) && isSamePlayingChannel(target) ->
                                    playProgramInFullscreen(null, target)
                                program.isLive(guideClockMillis) -> {
                                    // First selection of another live channel follows the same
                                    // guide contract: tune it in the mini-player.
                                    fullscreenGuideOpen = false
                                    isFullScreen = false
                                    playProgramInMini(target, null)
                                }
                            }
                        }
                    },
                    onLeftClick = {
                        fullscreenGuideOpen = false
                        quickZapOpen = true
                    },
                    modifier = Modifier,
                )
                QuickZapOverlay(
                    visible = isFullScreen && quickZapOpen,
                    currentChannel = playingChannel,
                    channels = filteredChannels,
                    nowNextMap = state.snapshot.nowNext,
                    categoriesTree = visibleEnrichedState.value.tree,
                    selectedCategoryId = selectedCategoryId,
                    onCategorySelected = { selectedCategoryId = it },
                    onDismiss = {
                        quickZapOpen = false
                        hudPokeSignal++
                    },
                    onChannelSelect = { channel ->
                        playingChannelId = channel.id
                        focusedChannelId = channel.id
                        epgPrefetchAnchorId = channel.id
                        playingCatchupProgram = null
                        catchupPlaybackOffsetMs = 0L
                        quickZapOpen = false
                        rememberedChannelByCategory[selectedCategoryId] = channel.id
                        hudPokeSignal++
                    },
                    onRightClick = { channel ->
                        guideChannel = channel
                        quickZapOpen = false
                        guideOpenedFromQuickZap = true
                        fullscreenGuideOpen = true
                    }
                )
            }
        }

        LaunchedEffect(isFullScreen, fullscreenGuideOpen, quickZapOpen, playingCatchupProgram) {
            if (isFullScreen && !fullscreenGuideOpen && !quickZapOpen) {
                delay(50L)
                runCatching { fsFocus.requestFocus() }
            }
        }

        // Top bar only shows when NOT in full-screen playback.
        // Fade with the fullscreen progress so it doesn't pop in/out — looks
        // natural next to the grow animation below.
        if (showTopBar && fsProgress < 1f) {
            Box(modifier = Modifier.graphicsLayer { alpha = 1f - fsProgress }) {
                AppTopBar(
                    selectedItem = SidebarItem.TV,
                    isFocused = focusZone == LiveTvFocusZone.TOPBAR,
                    focusedIndex = if (focusZone == LiveTvFocusZone.TOPBAR) topBarFocusIndex else -1,
                    profile = currentProfile,
                    profileCount = 1,
                )
            }
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SearchOverlay(
                channels = allDisplayChannels,
                nowNext = effectiveGuideNowNext,
                searchProvider = { query ->
                    viewModel.iptvRepository
                        .pagedSearchChannels(query, limit = 200)
                        .asSequence()
                        .filterNot { channel -> isAdultGroup(channel.group, channel.name) }
                        .filterNot { channel ->
                            val playlistId = StalkerPortalSupport.playlistIdFromChannelId(channel.id)
                            val groupKey = com.arflix.tv.data.model.PlaylistGroupKey
                                .build(playlistId, channel.group.ifBlank { "Ungrouped" })
                            groupKey in hiddenGroupSet
                        }
                        .mapIndexed { index, channel -> channel.enrichForFastStartup(index + 1) }
                        .toList()
                },
                onDismiss = { searchOpen = false },
                onPick = { channel ->
                    selectedCategoryId = bestCategoryIdForChannel(channel, visibleEnrichedState.value.tree)
                    playingChannelId = channel.id
                    focusedChannelId = channel.id
                    epgPrefetchAnchorId = channel.id
                    searchOpen = false
                    focusChannelList(channel.id)
                },
            )
        }

        if (!searchOpen) {
            val pickerChannel = variantPickerChannel
            VariantPickerOverlay(
                channel = pickerChannel,
                variants = pickerChannel?.let { variantGroups[variantGroupKey(it)] }.orEmpty(),
                onDismiss = { variantPickerChannel = null },
                onPick = { playVariant(it) },
            )
        }

        ChannelNumberOverlay(
            buffer = channelNumberBuffer,
            matchCount = channelNumberMatchCount,
            exactChannelName = channelNumberExactName,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = contentTopPadding + 24.dp, end = 32.dp),
        )

        PlaybackDiagnosticBanner(
            diagnostic = playbackDiagnostic,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isFullScreen) 72.dp else 24.dp),
        )

        if (programActionLookupInProgress) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE61A1A1A), RoundedCornerShape(8.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Pink,
                    strokeWidth = 3.dp,
                )
            }
        }

        val actionData = programActionDialog
        if (actionData != null) {
            val program = actionData.program
            val channel = actionData.channel
            val isNow = program.isLive(guideClockMillis)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { programActionDialog = null },
                title = {
                    androidx.tv.material3.Text(
                        text = program.title,
                        style = ArflixTypography.cardTitle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.tv.material3.Text(
                            text = channel.name,
                            style = ArflixTypography.caption,
                            color = TextSecondary,
                        )
                        androidx.tv.material3.Text(
                            text = "${formatClock(program.startUtcMillis)} - ${formatClock(program.endUtcMillis)}",
                            style = ArflixTypography.body,
                            color = TextSecondary,
                        )
                        if (isNow) {
                            Badge(stringResource(R.string.live_badge_live), Color.White, LiveColors.LiveRed)
                        }
                    }
                },
                confirmButton = {
                    val vodMatch = programActionVodMatch
                    if (vodMatch != null) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                invalidateProgramActionLookup()
                                onNavigateToDetails(vodMatch.mediaType, vodMatch.id)
                            },
                        ) {
                            androidx.tv.material3.Text(
                                text = stringResource(R.string.epg_search_sources),
                                style = ArflixTypography.button,
                                color = Pink,
                            )
                        }
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            programActionDialog = null
                            playProgramInMini(channel, epgWatchLivePlaybackProgram(program))
                        },
                    ) {
                        androidx.tv.material3.Text(
                            text = stringResource(R.string.epg_watch_live),
                            style = ArflixTypography.button,
                            color = TextSecondary,
                        )
                    }
                },
                containerColor = Color(0xFF1A1A1A),
                tonalElevation = 8.dp,
            )
        }
    }
}

/** State bundle of the enriched channel list + category tree. */
data class EnrichedChannels(
    val all: List<EnrichedChannel>,
    val tree: LiveCategoryTree,
    val index: LiveCategoryIndex = LiveCategoryIndex.Empty,
) {
    companion object {
        val Empty = EnrichedChannels(
            all = emptyList(),
            tree = LiveCategoryTree(
                top = emptyList(),
                global = LiveSection("global", "GLOBAL", emptyList()),
                countries = LiveSection("countries", "COUNTRIES", emptyList()),
                adult = LiveSection("adult", "ADULT", emptyList()),
            ),
        )
    }
}

private fun LiveCategoryTree.countForCategory(categoryId: String): Int? {
    fun Sequence<LiveCategory>.findCount(): Int? {
        for (category in this) {
            if (category.id == categoryId) return category.count
            val childCount = category.children.asSequence().findCount()
            if (childCount != null) return childCount
        }
        return null
    }
    return sequenceOf(
        top.asSequence(),
        global.categories.asSequence(),
        countries.categories.asSequence(),
        adult.categories.asSequence(),
        hidden.categories.asSequence(),
    ).flatten().findCount()
}

private val IptvGroupPipeSpacingRegex = Regex("""\s*\|\s*""")
private val IptvGroupWhitespaceRegex = Regex("""\s+""")

private fun looseIptvGroupKey(group: String?): String {
    return group.orEmpty()
        .trim()
        .replace(IptvGroupPipeSpacingRegex, "|")
        .replace(IptvGroupWhitespaceRegex, " ")
        .lowercase()
}

private fun compactIptvGroupKey(group: String?): String {
    return group.orEmpty()
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

private fun classifyPlaybackError(error: PlaybackException): String {
    httpResponseCode(error)?.let { return "provider returned HTTP $it" }
    val name = error.errorCodeName.lowercase()
    return when {
        "timeout" in name -> "network timeout"
        "network" in name || "io" in name -> "network or provider error"
        "parser" in name || "manifest" in name -> "stream format issue"
        "decoder" in name || "audio" in name || "video" in name -> "device codec issue"
        else -> "source did not start"
    }
}

private data class LiveTvBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int,
)

private fun buildLiveTvBufferProfile(
    memoryClassMb: Int,
    isLowRamDevice: Boolean,
): LiveTvBufferProfile {
    // Live-TV-appropriate buffering. The previous profile (96-160MB target,
    // 120-150s max buffer) treated a live stream like a VOD download: ExoPlayer's
    // buffer chunks are Java-heap byte arrays, so on a 384MB-capped TV box the
    // player alone could consume most of the heap — measured at 306-341MB with
    // 0% free, OOM-crashing the Live TV page during navigation. Live playback
    // only ever needs a ~30s safety window; smaller targets also start channels
    // faster and stop the initial bandwidth burn that caused early buffering.
    val heapMb = memoryClassMb.coerceAtLeast(256)
    val constrained = isLowRamDevice || heapMb <= 384
    // 32 MiB is too shallow for many 1080p/4K IPTV feeds: a brief Wi-Fi or
    // provider-rate dip drains it in seconds and presents as choppy playback.
    // 48 MiB stays far below the former 96–160 MiB OOM-prone allocation while
    // providing a useful live-stream safety margin on 384 MiB TV heaps.
    val targetMb = if (constrained) 48 else 64

    return LiveTvBufferProfile(
        minBufferMs = 20_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 1_500,
        bufferForPlaybackAfterRebufferMs = if (constrained) 3_500 else 4_000,
        targetBufferBytes = targetMb * 1024 * 1024,
        backBufferMs = 5_000,
    )
}

private fun httpResponseCode(error: PlaybackException): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return cause.responseCode
        }
        cause = cause.cause
    }
    return null
}

private fun redactPlaybackUrl(url: String): String {
    val withoutQuerySecrets = LiveTvScreenRegexes.QUERY_SECRETS
        .replace(url) { match -> "${match.groupValues[1]}***" }

    return LiveTvScreenRegexes.IPTV_URL_REDACT_REGEX
        .replace(withoutQuerySecrets) { match ->
            "${match.groupValues[1]}***/***${match.groupValues[4]}"
        }
        .take(260)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

internal fun epgWatchLivePlaybackProgram(
    @Suppress("UNUSED_PARAMETER") selectedProgram: IptvProgram,
): IptvProgram? = null

internal data class ProgramActionData(
    val channel: EnrichedChannel,
    val program: IptvProgram,
)
