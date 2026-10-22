package com.arflix.tv.management

import androidx.annotation.Keep
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ManagedFavouriteKey
import com.arflix.tv.data.repository.ManagedFavouriteState
import com.arflix.tv.data.repository.ProfileManager
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Keep
internal data class ManagementFavouriteEntry(val kind: String,val ref: String,val identity: String,val label: String,val available: Boolean)
@Keep
internal data class ManagementFavouriteSnapshot(val schema_version: Int,val profile_ref: String,val source_ref: String,val revision: String,val content_hash: String,val entries: List<ManagementFavouriteEntry>) {
    fun json()=JSONObject(Gson().toJson(this))
    override fun toString()="ManagementFavouriteSnapshot([redacted])"
}
@Keep
internal data class ManagementFavouritePreview(val schema_version: Int,val backup_id: String,val profile_ref: String,val source_ref: String,val expected_revision: String,
    val missing_refs: List<String>,val matched_channels: Int,val matched_groups: Int,val current_channels: Int,val current_groups: Int,val backup_channels: Int,val backup_groups: Int) {
    fun json()=JSONObject(Gson().toJson(this))
}

internal class ManagementFavouriteProtocol(secret: String,private val scope: String) {
    init {require(secret.matches(Regex("[a-f0-9]{64}")))}
    private val key=secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun mac(parts: Any): String = Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));doFinal(Gson().toJson(parts).toByteArray(Charsets.UTF_8))}.joinToString(""){"%02x".format(it)}
    fun source(state: ManagedFavouriteState)=mac(listOf("ECFAVSRC1",scope,state.profileId,state.privateSourceIdentity))
    fun ref(state: ManagedFavouriteState,item: ManagedFavouriteKey)=mac(listOf("ECFAVID1",source(state),item.kind,item.identity))
    fun revision(state: ManagedFavouriteState)=mac(listOf("ECFAVREV1",source(state),state.channelsRaw,state.groupsRaw))
    fun content(state: ManagedFavouriteState,channels: List<String> = state.channels,groups: List<String> = state.groups)=mac(listOf("ECFAVCONTENT1",source(state),channels,groups))
    fun snapshot(state: ManagedFavouriteState): ManagementFavouriteSnapshot {
        val items=state.items.associateBy { it.key }
        val keys=state.channels.map { ManagedFavouriteKey("channel",it) }+state.groups.map { ManagedFavouriteKey("group",it) }
        return ManagementFavouriteSnapshot(1,managementHash(state.profileId),source(state),revision(state),content(state),keys.map {
            val item=items.getValue(it);ManagementFavouriteEntry(it.kind,ref(state,it),it.identity,categoryLabel(item.title),item.available)
        })
    }
}

internal class ManagementFavouriteActions(private val repository: IptvRepository,private val profiles: ProfileManager,private val secret: String) {
    suspend fun capture(accountScope: String): ManagementFavouriteSnapshot {
        val profile=profiles.getProfileId();val state=repository.readManagedFavourites(profile) {managementAccount(it)?.scope==accountScope}
        check(profiles.getProfileId()==profile)
        return ManagementFavouriteProtocol(secret,accountScope).snapshot(state)
    }
    suspend fun execute(id: String,kind: String,accountScope: String,payload: Map<String,String>,backup: ManagementFavouriteSnapshot,preview: ManagementFavouritePreview?): CommandReceipt {
        val backupId=payload["backup_id"] ?: preview?.backup_id ?: return CommandReceipt(id,"failed","Missing favourites backup")
        return try {
            require(backupId.matches(Regex("[a-f0-9]{32}")) && backup.schema_version==1 && backup.entries.size in 1..1000)
            val profile=profiles.getProfileId()
            if(backup.profile_ref!=managementHash(profile))return CommandReceipt(id,"failed","Backup belongs to a different profile/installation; no favourites changed")
            val keys=backup.entries.map { ManagedFavouriteKey(it.kind,it.identity) }
            require(keys.distinct().size==keys.size && keys.all { it.kind in listOf("channel","group") && it.identity.toByteArray(Charsets.UTF_8).size in 1..512 && it.identity.none { c->c.isISOControl() } })
            val state=repository.readManagedFavourites(profile,keys) {managementAccount(it)?.scope==accountScope};val protocol=ManagementFavouriteProtocol(secret,accountScope)
            if(backup.source_ref!=protocol.source(state))return CommandReceipt(id,"failed","Source configuration changed since backup; no blind favourite restoration")
            require(backup.entries.all { protocol.ref(state,ManagedFavouriteKey(it.kind,it.identity))==it.ref })
            require(backup.content_hash==protocol.content(state,keys.filter { it.kind=="channel" }.map { it.identity },keys.filter { it.kind=="group" }.map { it.identity }))
            val available=state.items.filter { it.available }.map { it.key }.toSet();val missing=backup.entries.filter { ManagedFavouriteKey(it.kind,it.identity) !in available }.map { it.ref }
            val matched=keys.filter { it in available }
            if(kind=="preview_favourites_restore") {
                val p=ManagementFavouritePreview(1,backupId,backup.profile_ref,backup.source_ref,protocol.revision(state),missing,matched.count { it.kind=="channel" },matched.count { it.kind=="group" },state.channels.size,state.groups.size,keys.count { it.kind=="channel" },keys.count { it.kind=="group" })
                if(profiles.getProfileId()!=profile)return CommandReceipt(id,"failed","Active profile changed; request another preview")
                CommandReceipt(id,"succeeded","Favourites recovery preview ready; no data changed",favouritesPreview=p,favouritesBackupId=backupId)
            } else {
                require(kind=="restore_favourites" && preview!=null && payload["mode"] in listOf("merge","replace"))
                if(preview.backup_id!=backupId || preview.profile_ref!=backup.profile_ref || preview.source_ref!=backup.source_ref || preview.expected_revision!=protocol.revision(state) || preview.missing_refs.toSet()!=missing.toSet())return CommandReceipt(id,"failed","Favourites or channel availability changed since preview; preview again")
                if(matched.isEmpty())return CommandReceipt(id,"failed","No backed-up favourites currently resolve; open Live TV/reload the playlist before restoring")
                if(payload["mode"]=="replace" && missing.isNotEmpty())return CommandReceipt(id,"failed","Replace refused because some saved favourites no longer resolve; use reviewed merge recovery instead")
                val applied=repository.restoreManagedFavourites(state,matched,payload.getValue("mode")){managementAccount(it)?.scope==accountScope}
                if(applied)CommandReceipt(id,"succeeded","Recovered ${matched.size} favourites in one transaction; ${missing.size} missing entries skipped. Live confirmation follows the next check-in.")
                else CommandReceipt(id,"failed","Recovery conflict or limit exceeded; current favourites were not replaced. Preview again.")
            }
        } catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){CommandReceipt(id,"failed","Favourites backup/preview is invalid or unavailable; no blind restoration performed")}
    }
}
