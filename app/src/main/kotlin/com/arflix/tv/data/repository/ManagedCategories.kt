package com.arflix.tv.data.repository

import com.arflix.tv.data.model.PlaylistGroupKey

internal data class ManagedCategoryGroup(val key: String, val name: String, val channels: Int) { override fun toString()="ManagedCategoryGroup([redacted])" }
internal data class ManagedCategorySource(val id: String, val label: String, val privateIdentity: String, val groups: List<ManagedCategoryGroup>) {
    override fun toString() = "ManagedCategorySource([redacted])"
}
internal data class ManagedCategoryState(val profileId: String, val sources: List<ManagedCategorySource>, val hidden: List<String>, val order: List<String>,
    val hiddenRaw: String, val orderRaw: String, val schemaRaw: String) {
    val orderEditable: Boolean get() = orderRaw.isBlank() || schemaRaw == IPTV_GROUP_ORDER_SCHEMA.toString()
    override fun toString() = "ManagedCategoryState([redacted])"
}
internal data class ManagedCategoryPatch(val hidden: List<String>, val order: List<String>, val writesHidden: Boolean, val writesOrder: Boolean)

internal fun validManagedCategorySourceIds(ids: List<String>): Boolean = ids.size in 1..32 && ids.distinct().size==ids.size && ids.all { it.isNotBlank() && '|' !in it && ':' !in it }

internal fun guardedManagedCategoryPatch(expected: ManagedCategoryState,current: ManagedCategoryState,sourceId: String,operation: String,key: String?): ManagedCategoryPatch? {
    if(current!=expected)return null
    val source=current.sources.firstOrNull { it.id==sourceId } ?: return null
    return planManagedCategoryPatch(current,source,operation,key)
}

/** Source-scoped operations preserve other sources, unavailable keys and favourites. */
internal fun managedCategoryOrder(source: ManagedCategorySource, saved: List<String>): List<String> {
    val known=source.groups.map { it.key }.toSet()
    return saved.filter { it in known }.distinct() + source.groups.map { it.key }.filter { it !in saved }
}

internal fun planManagedCategoryPatch(state: ManagedCategoryState, source: ManagedCategorySource, operation: String, key: String?): ManagedCategoryPatch? {
    val known=source.groups.map { it.key }.toSet()
    if(operation!="reset_order" && key !in known) return null
    if(operation=="hide" || operation=="show") {
        val hidden=if(operation=="hide") (state.hidden + key!!).distinct() else state.hidden.filterNot { it==key }
        return ManagedCategoryPatch(hidden,state.order,true,false)
    }
    if(!state.orderEditable) return null
    if(operation=="reset_order") return ManagedCategoryPatch(state.hidden,state.order.filterNot { PlaylistGroupKey(it).playlistId==source.id },false,true)
    val ordered=managedCategoryOrder(source,state.order).toMutableList()
    val index=ordered.indexOf(key)
    if(index<0) return null
    val target=when(operation) {"move_up" -> (index-1).coerceAtLeast(0); "move_down" -> (index+1).coerceAtMost(ordered.lastIndex); "move_top" -> 0; else -> return null}
    ordered.removeAt(index);ordered.add(target,key!!)
    // Replace only known slots of this source. Unknown saved entries and the
    // relative order of every other source remain intact.
    val result=mutableListOf<String>();var next=0
    for(saved in state.order) {
        if(saved in known) {if(next<ordered.size)result+=ordered[next++]}
        else result+=saved
    }
    while(next<ordered.size)result+=ordered[next++]
    return ManagedCategoryPatch(state.hidden,result.distinct(),false,true)
}
