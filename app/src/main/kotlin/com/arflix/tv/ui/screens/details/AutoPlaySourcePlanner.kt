package com.arflix.tv.ui.screens.details

import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.repository.HomeServerRepository
import java.util.Locale

// Autoplay starts the best source it can find within this window when only remote
// (addon/debrid) candidates exist. It keeps collecting progressive addon results
// until every addon has reported OR this ceiling is reached.
internal const val AUTOPLAY_MAX_WAIT_MS = 2000L

// Extra grace granted while the best candidate so far is a REMOTE source, so the
// slower local-library probe (Jellyfin/Plex/Emby ~5s timeout, Xtream VOD) gets a
// chance to land before a debrid link is committed to. Resolving a debrid link is
// what actually spends Premiumize points, so this window is the whole saving.
internal const val AUTOPLAY_LOCAL_PROBE_EXTRA_MS = 1500L

// Once a top-tier (4K) LOCAL source is found we only briefly settle to let a
// larger/better local rip arrive, instead of waiting on slow addons.
internal const val AUTOPLAY_TOP_TIER_SETTLE_MS = 450L

internal const val AUTOPLAY_SOURCE_RECHECK_MS = 120L

// Total ceiling. Derived so tuning AUTOPLAY_MAX_WAIT_MS still behaves sensibly.
private const val AUTOPLAY_LOCAL_WINDOW_MS = AUTOPLAY_MAX_WAIT_MS + AUTOPLAY_LOCAL_PROBE_EXTRA_MS

private const val TOP_TIER_QUALITY_SCORE = 4

// Xtream VOD sources are injected by IptvRepository under this fixed addon id.
private const val XTREAM_VOD_ADDON_ID = "iptv_xtream_vod"

private object AutoPlayRegexes {
    val fourKRegex = Regex("""\b4[kK]\b""")
    val sizeRegex = Regex("""(?i)(\d+(?:[\.,]\d+)?)\s*(TB|GB|MB|KB|B|GiB|MiB|KiB)?""")
}

/**
 * True for sources served by the user's own infrastructure: a home media server
 * (Jellyfin / Plex / Emby) or their Xtream VOD library.
 *
 * These are free to stream — no debrid resolution, no points, no cache lookup —
 * so they are preferred over any addon result regardless of advertised quality.
 * Mirrors `DetailsViewModel.isSupplementalStream`, kept here so both the ranking
 * and the wait policy read from a single definition.
 */
internal fun isLocalLibraryStream(stream: StreamSource): Boolean =
    stream.addonId == XTREAM_VOD_ADDON_ID || stream.addonId == HomeServerRepository.ADDON_ID

/** Score quality from all stream text because addons do not fill the quality field consistently. */
internal fun qualityScoreForAutoPlay(stream: StreamSource): Int {
    val combined = buildString {
        append(stream.quality)
        append(' ')
        append(stream.source)
        append(' ')
        append(stream.addonName)
        stream.behaviorHints?.filename?.let {
            append(' ')
            append(it)
        }
        stream.description?.let {
            append(' ')
            append(it)
        }
    }

    return when {
        combined.contains("2160p", ignoreCase = true) || AutoPlayRegexes.fourKRegex.containsMatchIn(combined) -> 4
        combined.contains("1080p", ignoreCase = true) -> 3
        combined.contains("720p", ignoreCase = true) -> 2
        combined.contains("480p", ignoreCase = true) -> 1
        else -> 0
    }
}

internal fun bestAutoPlayStream(
    streams: List<StreamSource>,
    minQualityScore: Int
): StreamSource? {
    return streams
        .asSequence()
        .filter { stream ->
            // Local sources are exempt from the quality floor. HomeServerRepository
            // derives its quality label from videoWidth/videoHeight, and Plex parts
            // frequently leave those unset — the file would score 0 and be filtered
            // out of autoplay entirely, sending playback back to a paid source.
            isLocalLibraryStream(stream) || qualityScoreForAutoPlay(stream) >= minQualityScore
        }
        .sortedWith(
            // Own library first — that is the point of this ordering, and it wins
            // even against a higher-quality debrid rip because streaming it is free.
            // Within each tier: best quality, then biggest size, which is the user's
            // "best" definition.
            // `notWebReady` HTTP sources (e.g. direct MKV rips) are fully playable on
            // the native ExoPlayer, so they are eligible; webReady only breaks ties at
            // equal quality+size so a known-simple URL wins a coin-flip.
            compareByDescending<StreamSource> { if (isLocalLibraryStream(it)) 1 else 0 }
                .thenByDescending { qualityScoreForAutoPlay(it) }
                .thenByDescending { autoPlaySizeBytes(it) }
                .thenByDescending { if (it.behaviorHints?.notWebReady == true) 0 else 1 }
                .thenByDescending { if (it.behaviorHints?.cached == true) 1 else 0 }
                .thenBy { it.addonName.lowercase() }
                .thenBy { it.source.lowercase() }
        )
        .firstOrNull()
}

/**
 * The source sheet sorts from the visible size string because addon-provided
 * byte hints are inconsistent. Autoplay must do the same or it can choose a
 * tiny 720p source over a visibly larger 4K one.
 */
internal fun autoPlaySizeBytes(stream: StreamSource): Long {
    val raw = stream.size.trim()
    if (raw.isBlank()) return 0L
    val match = AutoPlayRegexes.sizeRegex.find(raw) ?: return 0L
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
    val unit = match.groupValues.getOrNull(2)?.uppercase(Locale.US).orEmpty()
    val multiplier = when (unit) {
        "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
        "MB", "MIB" -> 1024.0 * 1024.0
        "KB", "KIB" -> 1024.0
        else -> 1.0
    }
    return (value * multiplier).toLong()
}

internal fun minQualityThreshold(value: String): Int {
    return when (value.trim().lowercase()) {
        "720p", "hd" -> 2
        "1080p", "fullhd", "fhd" -> 3
        "4k", "2160p", "uhd" -> 4
        else -> 0
    }
}

internal fun isAutoPlayableStream(stream: StreamSource): Boolean {
    val url = stream.url?.trim().orEmpty()
    if (!url.startsWith("http", ignoreCase = true)) return false
    return !isPendingDebridStream(stream)
}

internal fun isPendingDebridStream(stream: StreamSource): Boolean {
    val text = listOfNotNull(stream.source, stream.addonName, stream.quality, stream.url, stream.description)
        .joinToString(" ")
        .lowercase()
    return listOf(
        "torrent being downloaded",
        "being downloaded",
        "still downloading",
        "queued",
        "not cached",
        "uncached",
        "cache pending",
        "caching",
        "processing torrent",
        "download in progress"
    ).any { text.contains(it) }
}

/**
 * Decides whether autoplay should keep waiting for more/better sources, or start now.
 *
 * Goal: play the user's own library when it has the title, and only fall back to a
 * paid/debrid source when it genuinely does not.
 *
 * - Hard ceiling at [AUTOPLAY_LOCAL_WINDOW_MS]: whatever is best by then plays.
 * - No candidate yet → wait while sources are still loading.
 * - LOCAL candidate → commit almost immediately. It already outranks everything
 *   else, so there is nothing cheaper to wait for; a brief
 *   [AUTOPLAY_TOP_TIER_SETTLE_MS] settle only lets a *better local* file (a 4K
 *   Jellyfin copy arriving after a 720p Xtream VOD one) win on quality.
 * - REMOTE candidate → keep waiting for the full window. The 4K shortcut is
 *   deliberately NOT applied here: a cached 4K debrid stream would otherwise
 *   commit at ~450ms and the local probe would never finish in time, which is
 *   exactly the leak this is meant to close.
 *
 * `isLoadingStreams` intentionally does not gate the remote branch — the
 * home-server and VOD probes run on their own jobs (`homeServerAppendJob`,
 * `vodAppendJob`) and are not reflected in that flag.
 */
internal fun shouldWaitForAutoPlaySources(
    isLoadingStreams: Boolean,
    selectedStream: StreamSource?,
    elapsedMs: Long
): Boolean {
    if (elapsedMs >= AUTOPLAY_LOCAL_WINDOW_MS) return false

    if (selectedStream == null) return isLoadingStreams

    if (isLocalLibraryStream(selectedStream)) {
        if (qualityScoreForAutoPlay(selectedStream) >= TOP_TIER_QUALITY_SCORE) return false
        return isLoadingStreams && elapsedMs < AUTOPLAY_TOP_TIER_SETTLE_MS
    }

    return true
}
