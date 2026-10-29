package com.arflix.tv.management

import android.content.Context
import android.os.SystemClock
import com.arflix.tv.BuildConfig
import com.arflix.tv.access.PrimaryAccessRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RenewalCheckoutState(val loading:Boolean=false,val url:String?=null,val simulated:Boolean=false,val message:String?=null,val qrExpired:Boolean=false)

/** Existing installation key authenticates a checkout-only nonce even when its
 * management bearer expired. No app lease/config/activation mutation on payment. */
class RenewalCheckoutRepository internal constructor(context:Context,private val primary:PrimaryAccessRepository){
    private val vault=ManagementVault(context)
    private val api=ManagementApi()
    private val mutex=Mutex()
    private val _state=MutableStateFlow(RenewalCheckoutState())
    val state=_state.asStateFlow()
    private var token:String?=null
    private var expected:Triple<String,String,Long>?=null
    private var qrExpiresAt=0L
    fun clear(){token=null;expected=null;qrExpiresAt=0L;_state.value=RenewalCheckoutState()}
    suspend fun load()=withContext(Dispatchers.IO){mutex.withLock{
        if(BuildConfig.BRAND_ID.isBlank()){clear();return@withLock}
        val identity=primary.renewalIdentity()?:run{clear();return@withLock}
        _state.value=RenewalCheckoutState(loading=true)
        try{
            val session=vault.load(identity.first)?:error("No saved management identity")
            check(session.policy !in setOf("blocked","suspended","removed")&&!session.recoveryPending)
            val device=session.deviceId?:error("No approved device")
            val key=vault.existingPublicKey(identity.first)?:error("Saved signing key unavailable")
            check(managementHash(key)==session.keyFingerprint)
            val body=JSONObject().put("device_id",device).put("app_package",BuildConfig.APPLICATION_ID)
                .put("key_fingerprint",session.keyFingerprint).put("primary_fingerprint",identity.second)
            val challenge=api.control("/api/device/renewals/challenge",body)
            if(!challenge.optBoolean("available")){clear();return@withLock}
            val nonce=challenge.getString("challenge").also{check(it.matches(Regex("[a-f0-9]{64}")))}
            val signature=vault.sign(identity.first,renewalSigningText(nonce,device,BuildConfig.APPLICATION_ID,session.keyFingerprint,identity.second))
            val result=api.control("/api/device/renewals/open",body.put("challenge",nonce).put("signature",signature))
            if(primary.renewalIdentity()!=identity){clear();return@withLock}
            if(!result.optBoolean("available")){clear();return@withLock}
            val url=result.getString("checkout_url");val capability=result.getString("session_token")
            check(validRenewalCheckout(url,capability,BuildConfig.BRAND_ID))
            check(result.getInt("expires_in") in 1..900)
            qrExpiresAt=SystemClock.elapsedRealtime()+result.getInt("expires_in")*1000L
            token=capability;expected=identity
            _state.value=RenewalCheckoutState(url=url,simulated=result.optBoolean("simulated"))
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){token=null;expected=null;_state.value=RenewalCheckoutState(message="Renewal is currently unavailable. Please contact your provider.")}
    }}
    suspend fun poll()=withContext(Dispatchers.IO){
        val capability=token?:return@withContext
        if(primary.renewalIdentity()!=expected){clear();return@withContext}
        if(SystemClock.elapsedRealtime()>=qrExpiresAt)_state.value=_state.value.copy(qrExpired=true)
        try{
            val result=api.control("/api/device/renewals/status",JSONObject().put("session_token",capability))
            if(primary.renewalIdentity()!=expected){clear();return@withContext}
            val order=result.optJSONObject("order")?:return@withContext
            val simulated=result.optBoolean("simulated")
            val message=when{
                order.optString("fulfilment_state")=="fulfilled" -> if(simulated)"Demo completed. No real account was renewed." else "Renewal completed. Verifying your account…"
                order.optString("payment_state")=="paid" -> "Payment received. Renewal is processing—do not pay again."
                order.optString("payment_state") in setOf("review","link_uncertain") -> "Your order needs verification. Please contact your provider; do not pay again."
                else -> null
            }
            _state.value=_state.value.copy(message=message,simulated=simulated)
            if(order.optString("fulfilment_state")=="fulfilled"&&!simulated)primary.retry()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){_state.value=_state.value.copy(message="Unable to check renewal status. Do not pay again; contact your provider if this continues.")}
    }
}

internal fun renewalSigningText(nonce:String,device:String,appPackage:String,key:String,primary:String)="ECRENEWCHECKOUT1\n$nonce\n$device\n$appPackage\n$key\n$primary"
internal fun validRenewalCheckout(url:String,token:String,brand:String):Boolean = brand.isNotBlank()&&token.matches(Regex("[a-f0-9]{64}"))&&url=="https://control.fecxbrfzku.net/renew/$token"
