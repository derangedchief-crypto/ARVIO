package com.arflix.tv.data.repository

import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.model.StreamSourceType
import com.arflix.tv.data.model.StreamSourceType.*

/**
 * Centralizes stream source ranking and selection logic for automatic playback.
 * Streams are ordered by type priority, then by quality/size within each type.
 *
 * Priority ranking:
 * 1. IPTV VOD (always first if available)
 * 2. Home servers (Jellyfin, Emby, Plex) - preferred/recommended first
 * 3. Stremio addons (health-scored, quality/size ranked as fallback)
 * 4. Torrent sources
 * 5. Telegram (lowest priority)
 */
class AutoPlaySourcePlanner(
    private val addonHealthMonitor: AddonHealthMonitor,
    private val iptv: IptvRepository,
    private val streamRepository: StreamRepository
) {

    /**
     * Plans stream selection for movie playback.
     * Filters and sorts streams for optimal playback order.
     */
    suspend fun planStreamSelection(
        streams: List<StreamSource>
    ): List<StreamSource> {
        if (streams.isEmpty()) return emptyList()

        // 1. Partition streams by type
        val iptvVod = streams.filter { it.type == IPTV_VOD }
        val homeServers = streams.filter { it.type in HOME_SERVER_TYPES }
        val stremioAddons = streams.filter { it.type == STREMIO }
        val torrents = streams.filter { it.type == TORRENT }
        val telegram = streams.filter { it.type == TELEGRAM }

        // 2. Sort home servers: recommended first (health scored), then rest
        val sortedHomeServers = homeServers.sortedWith(
            compareBy<StreamSource> {
                it.type !in listOf(JELLYFIN_RECOMMENDED, EMBY_RECOMMENDED, PLEX_RECOMMENDED)
            }
                .thenByDescending { addonHealthMonitor.getAddonHealthBias(it.addonId) }
        )

        // 3. Sort Stremio addons: health scored, quality/size ranked as fallback
        val sortedStremio = stremioAddons
            .sortedByDescending { addonHealthMonitor.getAddonHealthBias(it.addonId) }
            .thenByDescending { vodQualityRank(it.quality.ifBlank { it.source }) }
            .thenByDescending { vodQualityRank(it.source) }

        // 4. PRIORITY ORDER: IPTV VOD > Home Servers > Stremio > Torrents > Telegram
        return iptvVod + sortedHomeServers + sortedStremio + torrents + telegram
    }

    /**
     * Plans stream selection for episode playback.
     * Same logic as movie playback.
     */
    suspend fun planEpisodeSelection(
        streams: List<StreamSource>
    ): List<StreamSource> = planStreamSelection(streams)

    companion object {
        private val HOME_SERVER_TYPES = setOf(
            JELLYFIN,
            EMBY,
            JELLYFIN_RECOMMENDED,
            EMBY_RECOMMENDED,
            PLEX,
            PLEX_RECOMMENDED,
            HOME_SERVER
        )
    }
}