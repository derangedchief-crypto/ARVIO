package com.arflix.tv.trials

import com.arflix.tv.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Singleton
class TrialSignupRepository @Inject constructor(client: OkHttpClient,private val keys: TrialClientKeys,private val store: TrialPendingStore) {
    private val wire=TrialWireClient(client)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var shownOffer: TrialOffer?=null
    internal suspend fun offer(): TrialOffer=withContext(Dispatchers.IO){wire.offer().also { shownOffer=it }}
    internal suspend fun pending(): Boolean=withContext(Dispatchers.IO){BuildConfig.BRAND_ID.isNotEmpty()&&runCatching { store.load()!=null }.getOrDefault(false)}
    internal suspend fun start(first: String,phone: String): TrialProgress=withContext(Dispatchers.IO){
        check(BuildConfig.BRAND_ID.isNotBlank()) { "No reseller trial offer in this app." }
        val contact=TrialContact.parse(first,phone)
        val previous=store.load()
        if(previous!=null){
            require(previous.contact==contact) { "A previous trial request is still tracked. Retry that request rather than creating another." }
            return@withContext recover(previous)
        }
        val offer=shownOffer?.takeIf { it.available }?:error("Review the current trial offer first.")
        val session=wire.session(contact,keys.deviceClaim(),keys.publicKey(),offer.revision,keys::sign)
        val p=PendingTrial(session,contact);store.save(p) // Fail closed BEFORE financial submit if secure recovery fails.
        terminal(wire.submit(session,contact))
    }
    internal suspend fun retry(): TrialProgress=withContext(Dispatchers.IO){val p=store.load()?:error("No tracked trial request. Enter the same contact details to resume.");recover(p)}
    private fun recover(p: PendingTrial): TrialProgress {
        var pending=p
        if(System.currentTimeMillis()-p.session.createdAt>14*60_000L){
            // Same key + normalized phone/device resumes the original server operation.
            val offer=wire.offer().also { shownOffer=it };check(offer.available) { "The offer is no longer available. Your earlier trial may already exist; contact your seller." }
            val session=wire.session(p.contact,keys.deviceClaim(),keys.publicKey(),offer.revision,keys::sign)
            pending=PendingTrial(session,p.contact);store.save(pending)
            return terminal(wire.submit(pending.session,pending.contact))
        }
        val status=wire.status(pending.session)
        return terminal(if(status.state=="not_submitted")wire.submit(pending.session,pending.contact) else status)
    }
    private fun terminal(progress: TrialProgress): TrialProgress {if(progress.state in setOf("failed","cancelled","delivered"))store.clear();return progress}
    fun activationCompleted(){
        // Successful normal sign-in must not be blocked by local recovery cleanup.
        val p=runCatching { store.load() }.getOrNull();runCatching { store.clear() }
        if(p!=null)scope.launch { runCatching { wire.acknowledge(p.session) } }
    }
}
