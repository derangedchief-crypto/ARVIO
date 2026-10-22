package com.arflix.tv.management

import androidx.annotation.Keep
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ManagedCategorySource
import com.arflix.tv.data.repository.ManagedCategoryState
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.managedCategoryOrder
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Keep
internal data class ManagementCategorySource(val ref: String,val label: String,val total: Int)
@Keep
internal data class ManagementCategoryItem(val ref: String,val label: String,val channels: Int,val hidden: Boolean,val position: Int)
@Keep
internal data class ManagementCategoryCatalog(val profileRef: String,val sourceRef: String,val revision: String,val sources: List<ManagementCategorySource>,
    val items: List<ManagementCategoryItem>,val page: Int,val pages: Int,val total: Int,val orderEditable: Boolean) {
    fun json(): JSONObject = JSONObject().put("schema_version",1).put("profile_ref",profileRef).put("source_ref",sourceRef).put("revision",revision)
        .put("page",page).put("pages",pages).put("total",total).put("order_editable",orderEditable)
        .put("sources",JSONArray().also { array -> sources.forEach { array.put(JSONObject().put("ref",it.ref).put("label",it.label).put("total",it.total)) } })
        .put("items",JSONArray().also { array -> items.forEach { array.put(JSONObject().put("ref",it.ref).put("label",it.label).put("channels",it.channels).put("hidden",it.hidden).put("position",it.position)) } })
}

internal fun categoryLabel(raw: String): String {
    if(Regex("(?i)([a-z][a-z0-9+.-]*://|www\\.|bearer\\s|(?:password|passwd|username|token|secret|authorization|activation[_ -]?code|mac)\\s*[:=])").containsMatchIn(raw)) return "[redacted category label]"
    val text=raw.filterNot { it.isISOControl() }.trim()
    val points=text.codePoints().limit(80).toArray()
    return String(points,0,points.size).ifBlank { "Unnamed category" }
}

/** HMAC (not a public password hash) protects source identities/opaque row refs. */
internal class ManagementCategoryProtocol(private val secret: String,private val accountScope: String) {
    init { require(secret.matches(Regex("[a-f0-9]{64}"))) }
    private val key=secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val gson=Gson()
    private val sourceRefs=mutableMapOf<Triple<String,String,String>,String>()
    private fun mac(parts: Any): String {
        val bytes=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256"));doFinal(gson.toJson(parts).toByteArray(Charsets.UTF_8)) }
        val hex="0123456789abcdef";return buildString(bytes.size*2){for(byte in bytes){val value=byte.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])}}
    }
    fun sourceRef(state: ManagedCategoryState,source: ManagedCategorySource)=sourceRefs.getOrPut(Triple(state.profileId,source.id,source.privateIdentity)){mac(listOf("ECCATSRC1",accountScope,state.profileId,source.id,source.privateIdentity))}
    fun groupRef(state: ManagedCategoryState,source: ManagedCategorySource,key: String)=mac(listOf("ECCATROW1",sourceRef(state,source),key))
    fun revision(state: ManagedCategoryState,source: ManagedCategorySource)=mac(listOf("ECCATREV1",sourceRef(state,source),state.hiddenRaw,state.orderRaw,state.schemaRaw,source.groups.map { listOf(it.key,it.channels) }))
    fun catalog(state: ManagedCategoryState,source: ManagedCategorySource,page: Int): ManagementCategoryCatalog {
        val ordered=managedCategoryOrder(source,state.order);require(ordered.isNotEmpty() && ordered.size<=2000)
        val pages=(ordered.size+49)/50;require(page in 1..pages)
        val groups=source.groups.associateBy { it.key };val start=(page-1)*50
        return ManagementCategoryCatalog(managementHash(state.profileId),sourceRef(state,source),revision(state,source),
            state.sources.map { ManagementCategorySource(sourceRef(state,it),it.label,it.groups.size) },
            ordered.drop(start).take(50).mapIndexed { index,key -> val group=groups.getValue(key);ManagementCategoryItem(groupRef(state,source,key),categoryLabel(group.name),group.channels,key in state.hidden,start+index+1) },
            page,pages,ordered.size,state.orderEditable)
    }
}

internal fun validCategoryPayload(kind: String,payload: Map<String,String>): Boolean {
    val refs=listOf("profile_ref","source_ref","expected_revision","category_ref")
    if(payload.any { (key,value)->key in refs && !value.matches(Regex("[a-f0-9]{64}")) })return false
    if(payload["page"]?.let { it.toIntOrNull()?.let { n->n !in 1..40 || n.toString()!=it } ?: true }==true)return false
    if(kind=="get_categories")return payload.keys.all { it in listOf("profile_ref","source_ref","page") } && (("source_ref" in payload)==("profile_ref" in payload))
    if(kind!="set_categories"||!payload.keys.containsAll(listOf("profile_ref","source_ref","expected_revision","operation")))return false
    if(payload.keys.any { it !in refs+listOf("operation") })return false
    val op=payload["operation"]
    return op in listOf("hide","show","move_up","move_down","move_top","reset_order") && (("category_ref" in payload)==(op!="reset_order"))
}

/** Read retries may refresh the SAME target; mutations never rerun from receipts. */
internal fun categoryRetryPayload(receipt: CommandReceipt): Map<String,String>? {
    val profile=receipt.categoryProfileRef ?: return null;val source=receipt.categorySourceRef ?: return null
    val payload=mapOf("profile_ref" to profile,"source_ref" to source,"page" to receipt.categoryPage.toString())
    return payload.takeIf { receipt.status=="succeeded" && validCategoryPayload("get_categories",it) }
}

internal class ManagementCategoryActions(private val repository: IptvRepository,private val profiles: ProfileManager,private val secret: String) {
    suspend fun execute(id: String,kind: String,accountScope: String,payload: Map<String,String>): CommandReceipt {
        if(!validCategoryPayload(kind,payload))return CommandReceipt(id,"failed","Invalid category action")
        val profile=profiles.getProfileId()
        if(payload["profile_ref"]?.let { it!=managementHash(profile) }==true)return CommandReceipt(id,"failed","Active profile changed; request its current categories")
        return try {
            val state=repository.readManagedCategories(profile) { managementAccount(it)?.scope==accountScope }
            val protocol=ManagementCategoryProtocol(secret,accountScope)
            val source=if(payload["source_ref"]==null)state.sources.firstOrNull { it.groups.isNotEmpty() } else state.sources.firstOrNull { protocol.sourceRef(state,it)==payload["source_ref"] }
            if(source==null)return CommandReceipt(id,"failed","Source changed or no cached categories are available; open Live TV or reload the playlist first")
            if(kind=="get_categories") {
                val page=payload["page"]?.toInt() ?: 1;val catalog=protocol.catalog(state,source,page)
                CommandReceipt(id,"succeeded","Category snapshot read from the selected profile/source cache; not live provider data",catalog=catalog,
                    categoryProfileRef=catalog.profileRef,categorySourceRef=catalog.sourceRef,categoryPage=page)
            } else {
                if(protocol.revision(state,source)!=payload["expected_revision"])return CommandReceipt(id,"failed","Category settings or catalog changed; request a fresh snapshot")
                val key=payload["category_ref"]?.let { ref -> source.groups.firstOrNull { protocol.groupRef(state,source,it.key)==ref }?.key }
                if(payload["operation"]!="reset_order"&&key==null)return CommandReceipt(id,"failed","Category no longer belongs to the selected source")
                val applied=repository.applyManagedCategoryPatch(state,source.id,payload.getValue("operation"),key) { managementAccount(it)?.scope==accountScope }
                if(applied)CommandReceipt(id,"succeeded","Category ${payload.getValue("operation")} applied to the selected source only; request a fresh snapshot to confirm")
                else CommandReceipt(id,"failed","Profile, source, catalog or preferences changed; request a fresh snapshot (legacy ordering may need local migration)")
            }
        } catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){CommandReceipt(id,"failed","Category action unavailable; open Live TV/reload the playlist and request a fresh snapshot. Saved data was not cleared.")}
    }
}
