package com.arflix.tv.data.repository

import java.net.URI

/** Logical view only. Checkpoints preserve the original legacy storage shape. */
internal fun normalizedManagedSources(config: IptvConfig): IptvConfig {
    val lists=config.playlists.ifEmpty {if(config.m3uUrl.isBlank())emptyList() else listOf(IptvPlaylistEntry("list_1","List 1",config.m3uUrl,config.epgUrl))}
    return config.copy(playlists=lists,sortOrder="provider")
}
internal fun validManagedSourceConfig(before: IptvConfig,requested: IptvConfig,restoreCheckpoint: Boolean=false): Boolean = runCatching {
    fun url(value: String,allowEmpty: Boolean=false): Boolean {
        if(value.isEmpty())return allowEmpty
        val uri=URI(value)
        return value.length<=4096&&value.none {it.code<32}&&uri.scheme in listOf("http","https")&&!uri.host.isNullOrBlank()&&uri.userInfo==null
    }
    val target=normalizedManagedSources(requested);val original=normalizedManagedSources(before)
    val primary=original.playlists.firstOrNull()?:return@runCatching false
    // Preserve the existing repository's all-disabled legacy fallback; do not
    // advertise a remote "disable all" operation that the current app cannot honor.
    if(target.playlists.none {it.enabled})return@runCatching false
    if(target.playlists.firstOrNull()?.id!=primary.id||target.playlists.first().m3uUrl!=primary.m3uUrl||target.m3uUrl!=primary.m3uUrl||target.epgUrl!=target.playlists.first().epgUrl)return@runCatching false
    val ids=target.playlists.map {it.id}+target.stalkerPortals.map {it.id}
    if(!restoreCheckpoint) {
        val oldLists=original.playlists.associateBy {it.id};val oldPortals=original.stalkerPortals.associateBy {it.id}
        if(target.playlists.any {p->oldLists[p.id]?.let {it.m3uUrl!=p.m3uUrl}==true}||target.stalkerPortals.any {p->oldPortals[p.id]?.let {it.portalUrl!=p.portalUrl||it.macAddress!=p.macAddress}==true})return@runCatching false
    }
    if(target.playlists.size !in 1..3||target.stalkerPortals.size>3||ids.toSet().size!=ids.size||ids.any {!it.matches(Regex("[A-Za-z0-9_-]{1,64}"))})return@runCatching false
    if(target.playlists.any {it.name.length !in 1..80||!url(it.m3uUrl)||!url(it.epgUrl,true)||it.epgUrls.size>10||it.epgUrls.any {guide->!url(guide)}})return@runCatching false
    if(target.stalkerPortals.any {it.name.length !in 1..80||!url(it.portalUrl)||!it.macAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))})return@runCatching false
    true
}.getOrDefault(false)
