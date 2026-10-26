package com.arflix.tv.data.repository

/** Only new/replaced secondary identities need payment; viewing/metadata/removal do not. */
internal fun needsExtraPlaylistLicence(before: IptvConfig, target: IptvConfig): Boolean {
    fun lists(c: IptvConfig) = c.playlists.ifEmpty {
        if (c.m3uUrl.isBlank()) emptyList() else listOf(IptvPlaylistEntry("list_1", "List 1", c.m3uUrl, c.epgUrl))
    }
    val oldLists = lists(before).associate { it.id to it.m3uUrl }
    val oldPortals = before.stalkerPortals.associate { it.id to (it.portalUrl.trimEnd('/') to it.macAddress.uppercase()) }
    val firstActivation = if (lists(before).isEmpty() && lists(target).isNotEmpty()) 1 else 0
    if (lists(target).size + target.stalkerPortals.size > lists(before).size + before.stalkerPortals.size + firstActivation) return true
    val total = lists(target).size + target.stalkerPortals.size
    val ids=lists(target).map { it.id }+target.stalkerPortals.map { it.id }
    if(ids.distinct().size != ids.size) return true
    return lists(target).withIndex().any { (index,p) ->
        val baseChange = index==0 && (p.id=="list_1" || total==1 || lists(before).isEmpty())
        oldLists[p.id] != p.m3uUrl && !baseChange
    } || target.stalkerPortals.any {
        oldPortals[it.id] != (it.portalUrl.trimEnd('/') to it.macAddress.uppercase())
    }
}
