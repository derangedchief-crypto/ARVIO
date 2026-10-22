package com.arflix.tv.data.repository

internal data class ManagedFavouriteKey(val kind: String,val identity: String)
internal data class ManagedFavouriteItem(val key: ManagedFavouriteKey,val title: String,val available: Boolean)
internal data class ManagedFavouriteState(val profileId: String,val privateSourceIdentity: String,val channels: List<String>,val groups: List<String>,
    val channelsRaw: String,val groupsRaw: String,val items: List<ManagedFavouriteItem>) {
    override fun toString()="ManagedFavouriteState([redacted])"
}
internal data class ManagedFavouritePatch(val channels: List<String>,val groups: List<String>)

internal fun planManagedFavouriteRestore(state: ManagedFavouriteState,keys: List<ManagedFavouriteKey>,mode: String): ManagedFavouritePatch? {
    if(mode !in listOf("merge","replace") || keys.isEmpty() || keys.distinct().size!=keys.size || keys.any { it.kind !in listOf("channel","group") })return null
    val available=state.items.filter { it.available }.map { it.key }.toSet()
    if(keys.any { it !in available })return null
    val channels=keys.filter { it.kind=="channel" }.map { it.identity };val groups=keys.filter { it.kind=="group" }.map { it.identity }
    val result=if(mode=="merge")ManagedFavouritePatch((state.channels+channels).distinct(),(state.groups+groups).distinct()) else ManagedFavouritePatch(channels,groups)
    return result.takeIf { it.channels.size+it.groups.size<=1000 }
}
