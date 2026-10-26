package com.arflix.tv.access

import android.content.Context
import android.os.SystemClock
import com.arflix.tv.data.repository.IptvActivationCredentials
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvPlaylistEntry
import com.arflix.tv.management.ExtraPlaylistLicence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide primary authority. No playlist scanning, no clock-based persisted
 * unlock, no per-zap network call. Explicit negatives discard prior permission. */
@Singleton
class PrimaryAccessRepository @Inject constructor(@ApplicationContext context:Context) {
    private val vault=ActivationVault(context)
    private val licences=ExtraPlaylistLicence(context)
    private val verifier=PrimaryLineVerifier()
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var anchor:ActivationAnchor?=null
    @Volatile private var primary:AccessLease?=null
    @Volatile private var extras:AccessLease?=null
    @Volatile private var extrasGraceEligible=false
    @Volatile private var initialized=false
    @Volatile private var initialValidationPending=true
    @Volatile private var activationPending=false
    @Volatile private var bindingRevision=0L
    @Volatile private var primaryMessage="Confirm your activation code to secure this installation. Saved playlists and favourites stay intact."
    private var started=false
    @Volatile private var foreground=true
    private val playbackConsumers=AtomicInteger()
    private val primaryUrls=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val _state=MutableStateFlow(PrimaryAccessState())
    val state=_state.asStateFlow()
    fun start()=synchronized(this){
        if(started)return@synchronized
        started=true
        scope.launch{while(isActive){if(foreground||playbackConsumers.get()>0)refresh();delay(60_000)}}
        scope.launch{while(isActive){publish();delay(250)}}
    }
    fun retry(){scope.launch{refresh()}}
    fun foreground(active:Boolean){foreground=active;if(active)retry()}
    fun watchPlayback():()->Unit {playbackConsumers.incrementAndGet();return {playbackConsumers.decrementAndGet()}}
    fun primaryAllowed():Boolean {val a=anchor?:return false;return primary?.valid(a.identity,SystemClock.elapsedRealtime())==true}
    fun extrasAllowed():Boolean {val a=anchor?:return false;return primaryAllowed()&&extras?.valid(a.identity,SystemClock.elapsedRealtime())==true}
    private fun publish(){
        if(activationPending)return
        val old=_state.value;val allowed=primaryAllowed();val extra=extrasAllowed()
        val epoch=old.restrictionEpoch+if((old.primaryAllowed&&!allowed)||old.bindingRevision!=bindingRevision)1 else 0
        _state.value=PrimaryAccessState(!initialized||initialValidationPending,anchor!=null,allowed,extra,if(allowed)"Activation line verified" else primaryMessage,epoch,bindingRevision)
    }
    private fun canonical(a:ActivationAnchor)=IptvPlaylistEntry("list_1","Primary activation",
        "$PRIMARY_HOST/get.php?username=${java.net.URLEncoder.encode(a.username,"UTF-8")}&password=${java.net.URLEncoder.encode(a.password,"UTF-8")}&type=m3u_plus&output=ts")
    fun canonicalConfig():IptvConfig {val a=anchor?:throw IllegalStateException("Confirm the primary activation code first.");val p=canonical(a);return IptvConfig(m3uUrl=p.m3uUrl,playlists=listOf(p))}
    fun isPrimary(raw:String)=anchor?.matches(raw)==true
    fun needsExtraGrant(before:IptvConfig,target:IptvConfig)=requiresExtraGrant(anchor?:throw com.arflix.tv.management.ExtraPlaylistLicenceException("Confirm activation first."),before,target)
    fun permitsIptvUrl(url:String):Boolean {
        val a=anchor?:return false
        return primaryAllowed()&&(extrasAllowed()||a.matchesStream(url)||primaryUrls[url]==a.identity)
    }
    fun registerResolvedPrimary(original:String,resolved:String){val a=anchor?:return;if(primaryAllowed()&&a.matchesStream(original)){if(primaryUrls.size>2048)primaryUrls.clear();primaryUrls[resolved]=a.identity}}
    fun editingConfig(raw:IptvConfig):IptvConfig {
        val a=anchor?:return raw
        val lists=raw.playlists.ifEmpty{if(raw.m3uUrl.isBlank())emptyList() else listOf(IptvPlaylistEntry("list_1","Primary",raw.m3uUrl,raw.epgUrl))}
        var synthetic="activation_${a.identity.take(16)}"
        while(lists.any{it.id==synthetic})synthetic+="p"
        val main=lists.firstOrNull{a.matches(it.m3uUrl)}?.copy(enabled=true)?:canonical(a).copy(id=synthetic)
        return raw.copy(m3uUrl=main.m3uUrl,epgUrl=main.epgUrl,playlists=listOf(main)+lists.filterNot{it.id==main.id})
    }
    fun playbackConfig(raw:IptvConfig):IptvConfig {
        if(!primaryAllowed())return IptvConfig()
        val bound=editingConfig(raw)
        return if(extrasAllowed())bound else bound.copy(playlists=bound.playlists.take(1),stalkerPortals=emptyList())
    }
    /** Ordinary editors can never alter the binding or remove/disable a present primary. */
    fun protectPrimary(before:IptvConfig,target:IptvConfig){
        val a=anchor?:throw com.arflix.tv.management.ExtraPlaylistLicenceException("Confirm the activation code first.")
        val old=before.playlists.firstOrNull{a.matches(it.m3uUrl)}
        if(old!=null&&!target.playlists.any{it.id==old.id&&it.enabled&&a.matches(it.m3uUrl)})throw com.arflix.tv.management.ExtraPlaylistLicenceException("The activation line cannot be disabled, removed or replaced here. Use verified activation.")
    }
    private suspend fun verify(a:ActivationAnchor)=verifier.check(a)
    /** Called ONLY after the official activation-code resolver succeeded. Hold
     * public navigation in recovery until the submission callback finishes. */
    internal suspend fun bindVerified(credentials:IptvActivationCredentials,saveConfig:suspend()->Unit)=mutex.withLock {
        val candidate=ActivationAnchor(credentials.username,credentials.password)
        val decision=verify(candidate)
        check(decision is LineDecision.Valid){"The primary line is not confirmed active. Retry your activation code."}
        val checked=SystemClock.elapsedRealtime();val lease=AccessLease.grant(candidate.identity,checked,decision.seconds)
        activationPending=true
        try {
            saveConfig()
            vault.save(candidate)
            anchor=candidate;primary=lease;extras=null;extrasGraceEligible=false;bindingRevision++
            initialized=true;initialValidationPending=false;primaryMessage="Activation line verified"
        }catch(e:Throwable){activationPending=false;publish();throw e}
    }
    fun finishActivation(){activationPending=false;if(!primaryAllowed())primaryMessage="The primary verification expired during sign-in. Retry or renew the activation line.";publish()}
    suspend fun refresh()=mutex.withLock {
        if(activationPending)return@withLock
        if(!initialized){anchor=runCatching{vault.load()}.getOrNull();initialized=true}
        val a=anchor
        if(a==null){initialValidationPending=false;publish();return@withLock}
        when(val result=verify(a)){
            is LineDecision.Valid->{primary=AccessLease.grant(a.identity,SystemClock.elapsedRealtime(),result.seconds);primaryMessage="Primary validation is temporarily unavailable. Retry when connected."}
            LineDecision.Invalid->{primary=null;extras=null;primaryMessage="Your primary activation line is expired, disabled or invalid. Renew it or enter a valid activation code."}
            LineDecision.Unknown->{primaryMessage="Primary validation is unavailable. The 15-minute outage grace has ended, or no prior valid check exists."}
        }
        initialValidationPending=false
        publish()
        if(primaryAllowed()){
            try {
                val began=SystemClock.elapsedRealtime()
                val grant=licences.decision(canonicalConfig())
                if(grant.primaryInvalid){primary=null;extras=null;primaryMessage="Your primary activation line is expired, disabled or invalid. Renew it or enter a valid activation code."}
                else {extras=if(grant.allowed)AccessLease.grant(a.identity,began,grant.seconds) else null;extrasGraceEligible=grant.allowed&&grant.graceEligible}
            }catch(e:CancellationException){throw e}catch(_:Exception){if(!extrasGraceEligible)extras=null;/* Unknown never extends a paid lease. */}
        }
        publish()
    }
}
