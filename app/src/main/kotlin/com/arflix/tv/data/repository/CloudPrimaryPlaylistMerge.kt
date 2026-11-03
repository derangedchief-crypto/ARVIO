package com.arflix.tv.data.repository

/** Cloud backups are not activation authority. Keep the local binding and its
 * source ID, remapping cloud references when a backup uses a different ID. */
internal fun preserveCloudPrimary(
    state: IptvCloudProfileState,
    primary: IptvPlaylistEntry?,
    isPrimary: (String) -> Boolean,
): IptvCloudProfileState {
    if (primary == null) return state
    val incoming = state.playlists.ifEmpty {
        if (state.m3uUrl.isBlank()) emptyList() else listOf(
            IptvPlaylistEntry("list_1", "List 1", state.m3uUrl, state.epgUrl)
        )
    }
    val ids = mutableMapOf<String, String>()
    val used = (incoming.map { it.id } + state.stalkerPortals.map { it.id } + primary.id).toMutableSet()
    val extras = incoming.mapNotNull { entry ->
        if (isPrimary(entry.m3uUrl)) {
            ids[entry.id] = primary.id
            null
        } else if (entry.id == primary.id) {
            var id = "cloud_${entry.id}"
            while (!used.add(id)) id += "_1"
            ids[entry.id] = id
            entry.copy(id = id)
        } else entry
    }
    fun remap(value: String, separator: Char): String {
        val prefix = value.substringBefore(separator)
        return if (separator in value && prefix in ids) ids.getValue(prefix) + separator + value.substringAfter(separator) else value
    }
    fun remapGroup(value: String): String {
        val defaultId = incoming.firstOrNull()?.id
        val scoped = if ('|' !in value && defaultId != null) "$defaultId|$value" else value
        return remap(scoped, '|')
    }
    return state.copy(
        m3uUrl = primary.m3uUrl,
        epgUrl = primary.epgUrl,
        playlists = listOf(primary.copy(enabled = true)) + extras,
        favoriteGroups = state.favoriteGroups.map { remap(it, '|') },
        favoriteChannels = state.favoriteChannels.map { remap(it, ':') },
        hiddenGroups = state.hiddenGroups.map(::remapGroup),
        groupOrder = state.groupOrder.map(::remapGroup),
        tvSession = state.tvSession.copy(
            lastChannelId = remap(state.tvSession.lastChannelId, ':'),
            lastGroupName = remap(state.tvSession.lastGroupName, '|'),
            recentChannelIds = state.tvSession.recentChannelIds.map { remap(it, ':') },
        ),
    )
}
