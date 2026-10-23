package com.arflix.tv.trials

import com.arflix.tv.BuildConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class TrialContact(val firstName: String, val phone: String) {
    fun requestHash() = trialHash("trial-contact-v1\n$firstName\n$phone")
    companion object {
        fun parse(firstName: String, phone: String): TrialContact {
            val name=Normalizer.normalize(firstName.trim().replace(Regex(" +")," "),Normalizer.Form.NFC)
            require(name.length in 1..60 && name.matches(Regex("[\\p{L}\\p{M}][\\p{L}\\p{M} ’'-]*"))) { "Enter your first name." }
            var number=phone.trim().replace(Regex("[ ().-]"),"")
            if(number.startsWith("00"))number="+"+number.drop(2)
            require(number.matches(Regex("\\+[1-9][0-9]{7,14}"))) { "Include the international country code, for example +1." }
            return TrialContact(name,number)
        }
    }
}
internal fun trialHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT,it) }
internal data class TrialOffer(val available: Boolean, val brandName: String="",val duration: Int=0,val period: String="",val notice: String="",val revision: String="")
internal data class TrialSession(val token: String,val nonce: String,val operationId: String,val requestHash: String,val signature: String,val createdAt: Long) {
    fun proof(): JSONObject = JSONObject().put("token",token).put("nonce",nonce).put("request_hash",requestHash).put("signature",signature)
}
internal data class TrialProgress(val state: String,val activationCode: String?=null,val expiresAt: Long?=null)

/** Public brand routing is not app authentication. No worker/build secret exists here. */
internal class TrialWireClient(client: OkHttpClient,private val brandId: String=BuildConfig.BRAND_ID) {
    private val client=client.newBuilder().cache(null).connectTimeout(6,java.util.concurrent.TimeUnit.SECONDS).readTimeout(25,java.util.concurrent.TimeUnit.SECONDS).callTimeout(30,java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun offer(): TrialOffer {
        if(brandId.isBlank())return TrialOffer(false)
        require(brandId.matches(Regex("[a-f0-9]{32}"))) { "Trial offer unavailable." }
        val j=call("offer?brand_id=$brandId",null)
        if(!j.optBoolean("available"))return TrialOffer(false)
        val name=j.optString("brand_name");val duration=j.optInt("duration");val period=j.optString("period");val notice=j.optString("notice")
        val revision=j.optString("offer_revision")
        require(name.length in 1..60&&duration in 1..1000000&&period in listOf("hour","hours","day","days","week","weeks","month","months","year","years")&&j.optInt("notice_version")==1&&notice.length in 1..500&&revision.matches(Regex("[a-f0-9]{64}"))) { "Trial offer unavailable." }
        return TrialOffer(true,name,duration,period,notice,revision)
    }
    fun session(contact: TrialContact,deviceClaim: String,publicKey: String,offerRevision: String,sign: (String)->String): TrialSession {
        require(brandId.matches(Regex("[a-f0-9]{32}"))&&deviceClaim.matches(Regex("[a-f0-9]{64}"))) { "Trial setup unavailable." }
        require(offerRevision.matches(Regex("[a-f0-9]{64}"))) { "Review the current trial offer first." }
        val hash=contact.requestHash();val j=call("session",JSONObject().put("brand_id",brandId).put("device_claim",deviceClaim).put("public_key",publicKey).put("offer_revision",offerRevision).put("request_hash",hash))
        val token=j.getString("token");val nonce=j.getString("nonce");val op=j.getString("operation_id")
        require(token.matches(Regex("[a-f0-9]{64}"))&&nonce.matches(Regex("[a-f0-9]{64}"))&&op.matches(Regex("[a-f0-9]{32}"))) { "Invalid trial session." }
        val text="trial-submit-v1\n${trialHash(token)}\n$brandId\n$op\n$nonce\n$hash"
        require(j.optString("signing_text")==text&&j.optInt("expires_seconds") in 1..900) { "Trial request binding did not match." }
        return TrialSession(token,nonce,op,hash,sign(text),System.currentTimeMillis())
    }
    fun submit(session: TrialSession,contact: TrialContact): TrialProgress = progress(call("submit",session.proof().put("first_name",contact.firstName).put("phone",contact.phone).put("notice_accepted",true)))
    fun status(session: TrialSession): TrialProgress = progress(call("status",session.proof()))
    fun acknowledge(session: TrialSession) { call("ack",session.proof()) }
    private fun progress(j: JSONObject): TrialProgress {
        val state=j.optString("state");require(state in setOf("not_submitted","reserved","sending","retry_wait","registration_pending","needs_review","prepared","delivered","failed","cancelled","already_created")) { "Trial status unavailable." }
        val code=if(j.has("activation_code"))j.getString("activation_code") else null
        if(state=="prepared")require(code?.matches(Regex("[0-9]{6}"))==true&&j.optLong("expires_at")>System.currentTimeMillis()/1000) { "Trial activation has not been confirmed." }
        return TrialProgress(state,code,j.optLong("expires_at").takeIf { it>0 })
    }
    private fun call(path: String,body: JSONObject?): JSONObject {
        val builder=Request.Builder().url("https://control.fecxbrfzku.net/api/public-trials/$path").cacheControl(CacheControl.FORCE_NETWORK)
        if(body!=null)builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { r ->
            val bytes=r.body?.byteStream()?.use { input ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=65536) { "Trial response too large." };out.write(buffer,0,count)}
                out.toByteArray()
            } ?: error("Trial service unavailable.")
            if(!r.isSuccessful)error(if(r.code==429)"Trial capacity is temporarily limited. Please try later." else if(r.code==401)"Trial session expired. Retry the same trial setup; do not clear app data." else "Trial setup could not finish. Reopen the offer if no request is tracked; otherwise retry the existing request or contact your seller.")
            return try { JSONObject(String(bytes,Charsets.UTF_8)) } catch (_: Exception) { error("Invalid trial response. Retry the existing request.") }
        }
    }
}
