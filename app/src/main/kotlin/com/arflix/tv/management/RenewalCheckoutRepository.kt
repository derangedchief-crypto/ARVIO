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
import java.util.concurrent.atomic.AtomicInteger

data class RenewalCheckoutState(val loading:Boolean=false,val url:String?=null,val simulated:Boolean=false,val message:String?=null,val qrExpired:Boolean=false,val fulfilled:Boolean=false)

/** Checkout-only proof of an expired code or the bound primary. Management
 * enrollment is optional; payment never grants an app lease or changes activation. */
class RenewalCheckoutRepository internal constructor(context:Context,private val primary:PrimaryAccessRepository){
    private val login=RenewalLoginClient(context)
    private val api=ManagementApi()
    private val mutex=Mutex()
    private val _state=MutableStateFlow(RenewalCheckoutState())
    val state=_state.asStateFlow()
    @Volatile private var token:Pair<String,Int>?=null
    private var expected:Triple<String,String,Long>?=null
    private var qrExpiresAt=0L
    @Volatile private var loginCode:String?=null
    private val generation=AtomicInteger()
    fun clear(){generation.incrementAndGet();token=null;expected=null;loginCode=null;qrExpiresAt=0L;_state.value=RenewalCheckoutState()}
    suspend fun load()=withContext(Dispatchers.IO){mutex.withLock{
        if(BuildConfig.BRAND_ID.isBlank()){clear();return@withLock}
        val identity=primary.renewalIdentity()?:run{clear();return@withLock}
        val account=primary.renewalAccount()?:run{clear();return@withLock}
        clear();expected=identity
        open(JSONObject().put("username",account.username).put("password",account.password),identity.first)
    }}
    suspend fun loadCode(code:String)=withContext(Dispatchers.IO){mutex.withLock{
        clear()
        if(BuildConfig.BRAND_ID.isBlank()||!code.matches(Regex("[0-9]{6}")))return@withLock
        loginCode=code
        open(JSONObject().put("activation_code",code),null)
    }}
    suspend fun refresh(){val code=loginCode;if(code!=null)loadCode(code) else load()}
    private suspend fun open(identity:JSONObject,scope:String?){
        val attempt=generation.get()
        _state.value=RenewalCheckoutState(loading=true)
        try{
            val result=login.open(identity,scope)
            if(attempt!=generation.get())return
            if(expected!=null&&primary.renewalIdentity()!=expected){clear();return}
            if(!result.optBoolean("available")){
                _state.value=RenewalCheckoutState(message=when(result.optString("reason")){
                    "provider_setup_required"->"Your provider has not finished payment setup. Please contact your provider."
                    "package_price_unavailable"->"Renewal pricing for your current package is unavailable. Please contact your provider."
                    else->"Renewal is currently unavailable. Please contact your provider."
                });return
            }
            val url=result.getString("checkout_url");val capability=result.getString("session_token")
            check(validRenewalCheckout(url,capability,BuildConfig.BRAND_ID))
            check(result.getInt("expires_in") in 1..900)
            qrExpiresAt=SystemClock.elapsedRealtime()+result.getInt("expires_in")*1000L
            token=capability to attempt
            _state.value=RenewalCheckoutState(url=url,simulated=result.optBoolean("simulated"))
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){if(attempt==generation.get()){token=null;_state.value=RenewalCheckoutState(message=renewalFailureMessage((error as? ManagementHttpException)?.status))}}
    }
    suspend fun poll()=withContext(Dispatchers.IO){
        val (capability,attempt)=token?:return@withContext
        if(attempt!=generation.get())return@withContext
        if(expected!=null&&primary.renewalIdentity()!=expected){clear();return@withContext}
        if(SystemClock.elapsedRealtime()>=qrExpiresAt)_state.value=_state.value.copy(qrExpired=true)
        try{
            val result=api.control("/api/device/renewals/status",JSONObject().put("session_token",capability))
            if(attempt!=generation.get())return@withContext
            if(expected!=null&&primary.renewalIdentity()!=expected){clear();return@withContext}
            val order=result.optJSONObject("order")?:return@withContext
            val simulated=result.optBoolean("simulated")
            val message=when{
                order.optString("fulfilment_state")=="fulfilled" -> if(simulated)"Demo completed. No real account was renewed." else "Renewal completed. Verifying your account…"
                order.optString("payment_state")=="paid" -> "Payment received. Renewal is processing—do not pay again."
                order.optString("payment_state") in setOf("review","link_uncertain") -> "Your order needs verification. Please contact your provider; do not pay again."
                else -> null
            }
            _state.value=_state.value.copy(message=message,simulated=simulated,fulfilled=order.optString("fulfilment_state")=="fulfilled"&&!simulated)
            if(order.optString("fulfilment_state")=="fulfilled"&&!simulated)primary.retry()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){if(attempt==generation.get())_state.value=_state.value.copy(message="Unable to check renewal status. Do not pay again; contact your provider if this continues.")}
    }
}

internal fun renewalSigningText(nonce:String,device:String,appPackage:String,key:String,primary:String)="ECRENEWCHECKOUT1\n$nonce\n$device\n$appPackage\n$key\n$primary"
internal fun validRenewalCheckout(url:String,token:String,brand:String):Boolean = brand.isNotBlank()&&token.matches(Regex("[a-f0-9]{64}"))&&url=="https://control.fecxbrfzku.net/renew/$token"
internal fun renewalFailureMessage(status:Int?):String=when(status){
    403,409,422->"This account or device is not eligible for online renewal. Please contact your provider."
    429->"Too many renewal attempts. Please wait ten minutes and retry."
    else->"Unable to connect to renewal services. Please retry or contact your provider."
}
