package com.arflix.tv.ui.screens.tv.live

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.media3.ui.PlayerView
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import coil.compose.AsyncImage

/** TV-route-only Stage chrome. No shared movie/show navigation or theme is changed. */
internal object TvStageStyle {
    val Background = Color(0xFF10121B)
    val Surface = Color(0xFF1C2030)
    val Muted = Color(0xFF9CA6BE)
    val Accent = Color(0xFFB8B1FF)
}

@Composable
fun OwnTvProviderBar(
    providers: List<TvProviderFilter>, selectedId: String, onSelect: (String) -> Unit,
    focusRequester: FocusRequester? = null, onMoveUp: () -> Unit = {}, onMoveDown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (providers.size <= 1) return
    val guideInteractive = LocalLiveGuideInteractive.current
    Row(modifier.focusProperties { canFocus = guideInteractive }.horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.forEachIndexed { index, provider ->
            val selected = provider.id == selectedId
            TvStageAction("${provider.label} · ${provider.count}", selected = selected,
                modifier = Modifier
                    .then(if (focusRequester != null && (selected || (index == 0 && providers.none { it.id == selectedId })))
                        Modifier.focusRequester(focusRequester) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                            Key.DirectionUp -> { onMoveUp(); true }
                            Key.DirectionDown -> { onMoveDown(); true }
                            else -> false
                        }
                    }) { onSelect(provider.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OwnTvChannelLabel(
    channel: EnrichedChannel, clockTickMillis: Long, nowNext: IptvNowNext?,
    isActive: Boolean, isFavorite: Boolean, onClick: () -> Unit, onFavoriteToggle: () -> Unit,
    onMoveLeft: () -> Unit = {}, onMoveRight: () -> Boolean = { false },
    onMoveUp: () -> Boolean = { false }, onMoveDown: () -> Boolean = { false },
    onFocused: () -> Unit = {}, variantCount: Int = 1, onOpenVariants: () -> Unit = {},
    rowHeight: Dp = 64.dp, modifier: Modifier = Modifier,
) {
    val guideInteractive = LocalLiveGuideInteractive.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(rowHeight)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionLeft -> { onMoveLeft(); true }
                Key.DirectionRight -> onMoveRight()
                Key.DirectionUp -> onMoveUp()
                Key.DirectionDown -> onMoveDown()
                else -> false
            }
        }
        .padding(vertical = 3.dp, horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
        .background(if (focused) TvStageStyle.Accent.copy(alpha = 0.24f)
            else if (isActive) Color.White.copy(alpha = 0.08f) else Color.Transparent)
        .focusProperties { canFocus = guideInteractive }
        .combinedClickable(enabled = guideInteractive, onClick = onClick, onLongClick = {
            if (variantCount > 1) onOpenVariants() else onFavoriteToggle()
        }).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center) {
            if (!channel.logo.isNullOrBlank()) AsyncImage(channel.logo, contentDescription = null, modifier = Modifier.size(24.dp))
            else Text(channel.number.toString(), color = TvStageStyle.Accent, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().liveChannelNameMarquee(guideInteractive && (focused || isActive)),
                maxLines = 1, softWrap = false,
                overflow = if (focused || isActive) TextOverflow.Clip else TextOverflow.Ellipsis)
            Text(if (isFavorite) "★  ${channel.number}" else channel.number.toString(),
                color = TvStageStyle.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun OwnTvScreenHeader(
    profile: Profile?, focusedIndex: Int, onFocusIndex: (Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit, onSwitchProfile: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(82.dp).background(TvStageStyle.Background)
        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tv_stage_title), color = Color.White,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tv_stage_header_caption), color = TvStageStyle.Muted, fontSize = 10.sp)
        }
        for (index in 0..topBarMaxIndex(profile != null)) {
            val destination = topBarFocusedItem(index, profile != null)
            val label = if (destination == SidebarItem.TV) stringResource(R.string.tv_stage_live_tv)
                else destination?.let { stringResource(it.labelRes) } ?: stringResource(R.string.tv_stage_profile)
            TvStageAction(label, selected = if (focusedIndex >= 0) focusedIndex == index else destination == SidebarItem.TV,
                modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusIndex(index) }) {
                if (destination != null) onNavigate(destination) else onSwitchProfile()
            }
        }
    }
}

@Composable
fun OwnTvGuideHeading(channelCount: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tv_stage_guide), color = Color.White, fontSize = 16.sp,
            lineHeight = if (compact) 20.sp else TextUnit.Unspecified, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tv_stage_channel_count, channelCount), color = TvStageStyle.Muted, fontSize = 11.sp,
            lineHeight = if (compact) 16.sp else TextUnit.Unspecified)
    }
}

/** Navigation overlays the guide from the far right; no persistent header height. */
@Composable
fun TvGuideNavigationOverlay(
    profile: Profile?, visible: Boolean, focusedIndex: Int,
    onOpen: () -> Unit, onDismiss: () -> Unit, onFocusIndex: (Int) -> Unit,
    onNavigate: (SidebarItem) -> Unit, onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxIndex = topBarMaxIndex(profile != null)
    val drawerFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            withFrameNanos { }
            runCatching { drawerFocus.requestFocus() }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (!visible) {
            val label = stringResource(R.string.tv_guide_open_menu)
            TvStageAction("☰", dense = true, modifier = Modifier.align(Alignment.TopEnd)
                .padding(8.dp).semantics { contentDescription = label }, onClick = onOpen)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss))
            Column(Modifier.align(Alignment.CenterEnd).width(256.dp).fillMaxHeight()
                .background(TvStageStyle.Surface).padding(20.dp)
                .testTag("tv-navigation-drawer").focusRequester(drawerFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionUp -> { onFocusIndex((focusedIndex - 1).coerceAtLeast(0)); true }
                        Key.DirectionDown -> { onFocusIndex((focusedIndex + 1).coerceAtMost(maxIndex)); true }
                        Key.DirectionLeft, Key.Back, Key.Escape, Key.Menu -> { onDismiss(); true }
                        Key.DirectionRight -> true
                        Key.Enter, Key.DirectionCenter -> {
                            val destination = topBarFocusedItem(focusedIndex, profile != null)
                            if (destination != null) onNavigate(destination) else onSwitchProfile()
                            true
                        }
                        else -> false
                    }
                }.focusable(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tv_guide_navigation), color = Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.tv_guide_close_menu_hint), color = TvStageStyle.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                for (index in 0..maxIndex) {
                    val destination = topBarFocusedItem(index, profile != null)
                    val label = if (destination == SidebarItem.TV) stringResource(R.string.tv_stage_live_tv)
                        else destination?.let { stringResource(it.labelRes) } ?: stringResource(R.string.tv_stage_profile)
                    TvStageAction(label, selected = focusedIndex == index,
                        modifier = Modifier.fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) onFocusIndex(index) }) {
                        if (destination != null) onNavigate(destination) else onSwitchProfile()
                    }
                }
            }
        }
    }
}

internal data class TvStageFolder(val category: LiveCategory, val section: String, val hidden: Boolean = false)

internal fun tvStageFolders(tree: LiveCategoryTree, playlists: List<PlaylistCategorySection>): List<TvStageFolder> {
    val folders = mutableListOf<TvStageFolder>()
    val seen = mutableSetOf<String>()
    fun append(category: LiveCategory, section: String, hidden: Boolean = false) {
        if (seen.add(category.id)) folders += TvStageFolder(category, section, hidden)
        category.children.forEach { append(it, section, hidden) }
    }
    tree.top.forEach { append(it, "") }
    if (playlists.isNotEmpty()) playlists.forEach { section ->
        section.categories.forEach { append(it, section.label) }
    } else {
        tree.global.categories.forEach { append(it, tree.global.label) }
        tree.countries.categories.forEach { append(it, tree.countries.label) }
    }
    // Hidden categories belong only in Settings management, never TV navigation.
    return folders
}

@Composable
internal fun TvStageAction(label: String, selected: Boolean = false, modifier: Modifier = Modifier, dense: Boolean = false, onClick: () -> Unit) {
    val guideInteractive = LocalLiveGuideInteractive.current
    var focused by remember { mutableStateOf(false) }
    Text(label, color = if (selected || focused) Color(0xFF17142B) else Color.White,
        fontSize = if (dense) 11.sp else 12.sp, lineHeight = if (dense) 14.sp else TextUnit.Unspecified,
        fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected || focused) TvStageStyle.Accent else Color.White.copy(alpha = 0.07f))
            .focusProperties { canFocus = guideInteractive }
            .clickable(enabled = guideInteractive, onClick = onClick).padding(horizontal = if (dense) 10.dp else 14.dp, vertical = if (dense) 6.dp else 10.dp))
}

/** New preview stage: floating video plate + programme editorial panel, responsive on phones. */
@Composable
fun OwnTvPreviewStage(
    channel: EnrichedChannel?, clockTickMillis: Long, nowNext: IptvNowNext?, favoriteSet: Set<String>,
    onFavoriteToggle: (String) -> Unit, onFullscreenClick: (() -> Unit)? = null,
    variantCount: Int = 1, onOpenVariants: (() -> Unit)? = null,
    onPlayerViewCreated: ((PlayerView) -> Unit)? = null,
    onPlayerViewReleased: ((PlayerView) -> Unit)? = null,
    compact: Boolean = false, landscapeCompact: Boolean = false, modifier: Modifier = Modifier,
    guideMode: Boolean = false,
    surfaceResetKey: Any? = null,
    externalVideo: Boolean = false,
    onVideoBoundsChanged: (Rect) -> Unit = {},
) {
    val guideInteractive = LocalLiveGuideInteractive.current
    BoxWithConstraints(modifier.padding(horizontal = 16.dp, vertical = if (guideMode) 8.dp else 12.dp)) {
        val narrow = maxWidth < 520.dp
        val guideVideoWidth = (maxWidth * 0.32f).coerceIn(144.dp, 200.dp)
        @Composable fun video(modifier: Modifier) {
            Box(modifier.onGloballyPositioned { onVideoBoundsChanged(it.boundsInWindow()) }
                .then(if (externalVideo && channel != null) Modifier.clearAndSetSemantics {} else Modifier)
                .clip(RoundedCornerShape(18.dp)).background(Color.Black)
                .then(if (!externalVideo || channel == null)
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)) else Modifier)
                 .focusProperties { canFocus = guideInteractive }
                 .clickable(enabled = channel != null && guideInteractive) { onFullscreenClick?.invoke() }) {
                 if (channel != null && !externalVideo) key(surfaceResetKey) { AndroidView(
                    factory = { context ->
                        (LayoutInflater.from(context).inflate(R.layout.live_tv_player_view, null) as PlayerView)
                             .apply {
                                 useController = false; setKeepContentOnPlayerReset(true)
                                 isFocusable = false; isFocusableInTouchMode = false
                                 descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                             }
                            .also { onPlayerViewCreated?.invoke(it) }
                    },
                    update = { onPlayerViewCreated?.invoke(it) },
                    onRelease = { onPlayerViewReleased?.invoke(it) }, modifier = Modifier.fillMaxSize()) }
                 else if (channel == null) Text(stringResource(R.string.tv_stage_select_channel), color = TvStageStyle.Muted,
                    fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                 if (!externalVideo || channel == null) OwnTvPreviewVideoDecorations(channel)
            }
        }
        @Composable fun information(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(if (guideMode) 4.dp else 8.dp)) {
                Text(stringResource(R.string.tv_stage_on_now), color = TvStageStyle.Accent,
                    fontSize = 10.sp, lineHeight = if (guideMode) 12.sp else TextUnit.Unspecified,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(nowNext?.now?.title ?: channel?.name ?: stringResource(R.string.tv_stage_title),
                    color = Color.White, fontSize = if (guideMode) 18.sp else if (narrow) 19.sp else 25.sp,
                    lineHeight = if (guideMode) 22.sp else TextUnit.Unspecified,
                    fontWeight = FontWeight.Bold, maxLines = if (guideMode) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!narrow) Text(nowNext?.now?.description ?: stringResource(R.string.tv_stage_intro),
                    color = TvStageStyle.Muted, fontSize = if (guideMode) 11.sp else 12.sp,
                    lineHeight = if (guideMode) 14.sp else TextUnit.Unspecified,
                    maxLines = if (guideMode) 1 else 2, overflow = TextOverflow.Ellipsis)
                nowNext?.next?.let { next ->
                    Text(stringResource(R.string.tv_stage_next, next.title), color = TvStageStyle.Muted,
                        fontSize = 11.sp, lineHeight = if (guideMode) 14.sp else TextUnit.Unspecified,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (channel != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvStageAction(stringResource(R.string.tv_stage_watch), dense = guideMode, onClick = { onFullscreenClick?.invoke() })
                    TvStageAction(if (channel.id in favoriteSet) "★" else "☆",
                        selected = channel.id in favoriteSet, dense = guideMode, onClick = { onFavoriteToggle(channel.id) })
                    if (variantCount > 1) TvStageAction("${channel.quality.label} ▾", dense = guideMode, onClick = { onOpenVariants?.invoke() })
                }
            }
        }
        if (guideMode) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            video(Modifier.width(guideVideoWidth).aspectRatio(16f / 9f))
            information(Modifier.weight(1f))
        } else if (narrow) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            information(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            video(Modifier.weight(0.42f).widthIn(max = 360.dp).aspectRatio(16f / 9f))
            information(Modifier.weight(0.58f))
        }
    }
}

@Composable
internal fun BoxScope.OwnTvPreviewVideoDecorations(channel: EnrichedChannel?) {
    Text(stringResource(R.string.tv_stage_live), color = Color.White, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart)
            .padding(12.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFCF415E))
            .padding(horizontal = 8.dp, vertical = 4.dp))
    if (channel != null) Text("${channel.number} · ${channel.name}", color = Color.White,
        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(12.dp))
}

/** Replaces the old expanding country sidebar with a provider/folder browser. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OwnTvCategoryPanel(
    tree: LiveCategoryTree, selectedId: String, playlistSections: List<PlaylistCategorySection> = emptyList(),
    expanded: Boolean, listState: LazyListState, focusRequester: FocusRequester? = null,
    onSelect: (String) -> Unit, onOpenSearch: () -> Unit,
    onHideCategory: (String?, String) -> Unit = { _, _ -> },
    onUnhideCategory: (String?, String) -> Unit = { _, _ -> },
    onMoveCategoryUp: (String?, String) -> Unit = { _, _ -> },
    onMoveCategoryToTop: (String?, String) -> Unit = { _, _ -> },
    onMoveCategoryDown: (String?, String) -> Unit = { _, _ -> },
    onFocusEnter: () -> Unit = {}, onMoveRight: () -> Unit = {}, onMoveUpFromSearch: () -> Unit = {},
    focusSearchSignal: Int = 0, focusCategorySignal: Int = 0, isTouchDevice: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val guideInteractive = LocalLiveGuideInteractive.current
    val folders = remember(tree, playlistSections) { tvStageFolders(tree, playlistSections) }
    val selectedRequest = focusRequester ?: remember { FocusRequester() }
    val searchRequest = remember { FocusRequester() }
    var menu by remember { mutableStateOf<TvStageFolder?>(null) }
    LaunchedEffect(guideInteractive) { if (!guideInteractive) menu = null }
    val currentFocusEnter by rememberUpdatedState(onFocusEnter)
    LaunchedEffect(focusSearchSignal) {
        if (focusSearchSignal > 0) runCatching { searchRequest.requestFocus() }
    }
    LaunchedEffect(focusCategorySignal) {
        if (focusCategorySignal > 0) {
            val index = folders.indexOfFirst { it.category.id == selectedId }.coerceAtLeast(0)
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { selectedRequest.requestFocus() }
        }
    }
    Column(modifier.width(224.dp).background(TvStageStyle.Surface)
        .padding(horizontal = 12.dp, vertical = 18.dp)
        .onFocusChanged { if (it.hasFocus) currentFocusEnter() }) {
        Text(stringResource(R.string.tv_stage_library), color = TvStageStyle.Muted,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
        TvStageAction(stringResource(R.string.tv_stage_search), modifier = Modifier.fillMaxWidth()
            .focusRequester(searchRequest).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { onMoveUpFromSearch(); true } else false
            }, onClick = onOpenSearch)
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = listState, modifier = Modifier.focusProperties { canFocus = guideInteractive },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(folders, key = { _, folder -> "${folder.hidden}:${folder.category.id}" }) { index, folder ->
                val category = folder.category
                if (folder.section.isNotEmpty() && (index == 0 || folders[index - 1].section != folder.section)) {
                    Text(liveSectionLabel(folder.section), color = TvStageStyle.Muted, fontSize = 10.sp,
                        modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 8.dp))
                }
                var focused by remember(category.id) { mutableStateOf(false) }
                val active = category.id == selectedId
                Row(Modifier.fillMaxWidth()
                    .then(if (active || (index == 0 && folders.none { it.category.id == selectedId }))
                        Modifier.focusRequester(selectedRequest) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { onMoveRight(); true }
                        else if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && index == 0) {
                            searchRequest.requestFocus(); true
                        } else false
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active || focused) TvStageStyle.Accent.copy(alpha = if (focused) 0.28f else 0.14f) else Color.Transparent)
                    .focusProperties { canFocus = guideInteractive }
                    .combinedClickable(enabled = guideInteractive, onClick = { if (folder.hidden) menu = folder else onSelect(category.id) },
                        onLongClick = { if (category.playlistGroupName != null) menu = folder })
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(liveCategoryLabel(category.label), color = if (active) TvStageStyle.Accent else Color.White,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(category.count.toString(), color = TvStageStyle.Muted, fontSize = 10.sp)
                }
            }
        }
    }
    menu?.let { folder ->
        val category = folder.category
        val group = category.playlistGroupName
        AlertDialog(onDismissRequest = { menu = null }, containerColor = TvStageStyle.Surface,
            title = { Text(category.label, color = Color.White) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group != null) {
                    if (folder.hidden) TvStageAction(stringResource(R.string.tv_stage_unhide)) {
                        onUnhideCategory(category.playlistId, group); menu = null
                    } else {
                        TvStageAction(stringResource(R.string.tv_stage_hide)) { onHideCategory(category.playlistId, group); menu = null }
                        TvStageAction(stringResource(R.string.tv_stage_move_up)) { onMoveCategoryUp(category.playlistId, group); menu = null }
                        TvStageAction(stringResource(R.string.tv_stage_move_top)) { onMoveCategoryToTop(category.playlistId, group); menu = null }
                        TvStageAction(stringResource(R.string.tv_stage_move_down)) { onMoveCategoryDown(category.playlistId, group); menu = null }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { menu = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

/** Phone uses a category sheet, not the previous scrolling chip/country rail. */
@Composable
fun OwnTvCategorySheetBar(
    tree: LiveCategoryTree, selectedId: String, playlistSections: List<PlaylistCategorySection> = emptyList(),
    onSelect: (String) -> Unit, onOpenSearch: () -> Unit, modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val guideInteractive = LocalLiveGuideInteractive.current
    LaunchedEffect(guideInteractive) { if (!guideInteractive) open = false }
    val folders = remember(tree, playlistSections) { tvStageFolders(tree, playlistSections).filterNot { it.hidden } }
    val selected = folders.firstOrNull { it.category.id == selectedId }?.category
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TvStageAction((selected?.label ?: stringResource(R.string.tv_stage_categories)) + " ▾",
            modifier = Modifier.weight(1f)) { open = true }
        TvStageAction(stringResource(R.string.tv_stage_search), onClick = onOpenSearch)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = TvStageStyle.Surface,
        title = { Text(stringResource(R.string.tv_stage_categories), color = Color.White) },
        text = { LazyColumn(Modifier.heightIn(max = 380.dp)) {
            itemsIndexed(folders, key = { _, folder -> folder.category.id }) { _, folder ->
                Text(liveCategoryLabel(folder.category.label), color = if (folder.category.id == selectedId) TvStageStyle.Accent else Color.White,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(folder.category.id); open = false }
                        .padding(vertical = 14.dp), fontSize = 14.sp)
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } })
}
