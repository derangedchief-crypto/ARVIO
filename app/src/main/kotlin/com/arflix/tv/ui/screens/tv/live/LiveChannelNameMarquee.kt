package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only focused/playing rows animate; short names never overflow or move. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.liveChannelNameMarquee(active: Boolean): Modifier = if (!active) this else basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = 1_200,
    repeatDelayMillis = 1_200,
    velocity = 28.dp,
)
