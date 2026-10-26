package com.arflix.tv.access

import androidx.annotation.Keep
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

internal const val OUTAGE_GRACE_MS=15*60_000L
internal const val PRIMARY_HOST="https://tv.extremeiptv.net"

/** Only the official activation-code flow creates this binding. Never infer it
 * from playlist order, an editable source ID, or whichever account still works. */
@Keep
internal data class ActivationAnchor(val username:String,val password:String) {
    val identity:String=MessageDigest.getInstance("SHA-256").digest((username+"\n"+password).toByteArray()).joinToString(""){"%02x".format(it)}
    override fun toString()="ActivationAnchor([redacted])"
    fun matches(raw:String):Boolean {
        val parts=raw.trim().split(Regex("\\s+"),limit=3)
        val url=(if(parts.size==3)parts[0] else raw).toHttpUrlOrNull()?:return false
        if(url.scheme!="https"||url.host!="tv.extremeiptv.net"||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty())return false
        return (if(parts.size==3)parts[1] else url.queryParameter("username"))==username &&
            (if(parts.size==3)parts[2] else url.queryParameter("password"))==password
    }
    fun matchesStream(raw:String):Boolean {
        if(matches(raw))return true
        val url=raw.toHttpUrlOrNull()?:return false
        if(url.scheme!="https"||url.host!="tv.extremeiptv.net"||url.port!=443)return false
        val p=url.pathSegments
        val offset=if(p.firstOrNull() in listOf("live","movie","series","timeshift"))1 else 0
        return p.size>=offset+3&&p[offset]==username&&p[offset+1]==password
    }
}

/** Grants are memory-only: process/device restart requires fresh verification,
 * never a new grace window reconstructed from the editable wall clock. */
internal data class AccessLease(val identity:String,val checkedAt:Long,val until:Long) {
    fun valid(identity:String,now:Long)=this.identity==identity&&now>=checkedAt&&now<until&&until-checkedAt in 1..OUTAGE_GRACE_MS
    companion object {
        fun grant(identity:String,now:Long,seconds:Long)=AccessLease(identity,now,now+seconds.coerceIn(0,900)*1000L)
    }
}
internal sealed interface LineDecision {
    data class Valid(val seconds:Long):LineDecision
    data object Invalid:LineDecision
    data object Unknown:LineDecision
}
internal fun parseWorkerPrimary(json:JSONObject,elapsedMs:Long):LineDecision {
    if(json.optInt("schema_version")!=1||json.opt("active") !is Boolean)return LineDecision.Unknown
    if(!json.getBoolean("active"))return LineDecision.Invalid
    val seconds=json.optLong("valid_for_seconds",-1)
    if(seconds !in 1..900)return LineDecision.Unknown
    val remaining=seconds-(elapsedMs.coerceAtLeast(0)+999)/1000
    return if(remaining>0)LineDecision.Valid(remaining) else LineDecision.Invalid
}
internal fun requiresExtraGrant(anchor:ActivationAnchor,before:com.arflix.tv.data.repository.IptvConfig,target:com.arflix.tv.data.repository.IptvConfig):Boolean {
    fun lists(c:com.arflix.tv.data.repository.IptvConfig)=c.playlists.ifEmpty{if(c.m3uUrl.isBlank())emptyList()else listOf(com.arflix.tv.data.repository.IptvPlaylistEntry("list_1","Saved",c.m3uUrl,c.epgUrl))}
    val old=lists(before).associate{it.id to it.m3uUrl}
    if(lists(target).any{!anchor.matches(it.m3uUrl)&&old[it.id]!=it.m3uUrl})return true
    val portals=before.stalkerPortals.associate{it.id to (it.portalUrl to it.macAddress.uppercase())}
    return target.stalkerPortals.any{portals[it.id]!=(it.portalUrl to it.macAddress.uppercase())}
}

/** Server time only. auth=1 is NOT enough: status and a finite exp_date are
 * checked too. Network/HTML/malformed responses cannot mint a positive lease. */
internal fun parsePrimaryDecision(json:JSONObject,dateHeader:String?,elapsedMs:Long):LineDecision {
    val user=json.optJSONObject("user_info")?:return LineDecision.Unknown
    if(user.has("auth")&&user.optString("auth") in listOf("0","false"))return LineDecision.Invalid
    if(user.optString("auth")!="1")return LineDecision.Unknown
    val status=user.optString("status")
    if(status.lowercase() in setOf("expired","banned","disabled","inactive","deleted","removed","invalid","blocked"))return LineDecision.Invalid
    if(!status.equals("Active",true))return LineDecision.Unknown
    if(!user.has("exp_date"))return LineDecision.Unknown
    val raw=user.opt("exp_date")
    val unlimited=raw==JSONObject.NULL||raw.toString()=="0"
    val expiry:Long?=if(unlimited)null else (raw.toString().toLongOrNull()?.takeIf{it>0}?:return LineDecision.Unknown)
    val bodyTime=json.optJSONObject("server_info")?.optString("timestamp_now")?.toLongOrNull()?.takeIf{it>0}
    val headerTime=runCatching{ZonedDateTime.parse(dateHeader,DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()}.getOrNull()
    val server=listOfNotNull(bodyTime,headerTime).maxOrNull()
    if(expiry!=null&&server==null)return LineDecision.Unknown
    if(expiry!=null&&expiry<=server!!)return LineDecision.Invalid
    val seconds=minOf(900L,if(expiry==null)900L else expiry-server!!)-((elapsedMs.coerceAtLeast(0)+999)/1000)
    return if(seconds>0)LineDecision.Valid(seconds) else if(expiry!=null)LineDecision.Invalid else LineDecision.Unknown
}

data class PrimaryAccessState(
    val checking:Boolean=true,
    val bound:Boolean=false,
    val primaryAllowed:Boolean=false,
    val extrasAllowed:Boolean=false,
    val message:String="Checking activation line",
    val restrictionEpoch:Long=0,
    val bindingRevision:Long=0,
)
