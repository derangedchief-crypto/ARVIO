package com.arflix.tv.management

import android.content.Context
import android.util.Base64
import com.arflix.tv.BuildConfig
import org.json.JSONObject
import java.util.UUID

/** Checkout-only key possession. Never enrolls a device or mints a playback grant. */
internal class RenewalLoginClient(context:Context,
    private val vault:ManagementVault=ManagementVault(context),private val api:ManagementApi=ManagementApi(),
    private val brandId:String=BuildConfig.BRAND_ID,private val appPackage:String=BuildConfig.APPLICATION_ID,
    private val checkoutVault:ManagementVault=ManagementVault(context,"renewal-checkout-v1")) {
    suspend fun open(identity:JSONObject,accountScope:String?=null,country:String):JSONObject {
        check(brandId.isNotBlank())
        require(country in setOf("US","CA"))
        val known=accountScope?.let{vault.load(it)}
        check(known?.policy !in setOf("blocked","suspended","removed")&&known?.recoveryPending!=true)
        val scope=if(known!=null)accountScope!! else managementHash("renewal-checkout-device-v1\n$appPackage")
        val store=if(known!=null)vault else checkoutVault
        val saved=known?:store.load(scope)
        val key=if(saved==null)store.publicKey(scope) else store.existingPublicKey(scope)?:error("Saved checkout key unavailable")
        val fingerprint=managementHash(key)
        val session=saved?:ManagementSession(scope,UUID.randomUUID().toString().replace("-",""))
        check(session.keyFingerprint.isBlank()||session.keyFingerprint==fingerprint)
        if(known==null)store.save(session.copy(keyFingerprint=fingerprint))
        val body=JSONObject().put("installation_id",session.installationId).put("app_package",appPackage)
        val challenge=api.control("/api/device/renewals/login/challenge",JSONObject(body.toString()).put("key_fingerprint",fingerprint))
        val nonce=challenge.getString("challenge").also{check(it.matches(Regex("[a-f0-9]{64}")))}
        val digest=if(identity.has("activation_code"))renewalCodeDigest(identity.getString("activation_code"))
            else renewalAccountDigest(identity.getString("username"),identity.getString("password"))
        val signature=store.sign(scope,renewalLoginSigningText(nonce,session.installationId,appPackage,fingerprint,digest,country))
        return api.control("/api/device/renewals/login/open",body.put("challenge",nonce).put("identity",identity)
            .put("public_key",Base64.encodeToString(key,Base64.NO_WRAP)).put("signature",signature).put("country",country))
    }
}

internal fun renewalCodeDigest(code:String)=managementHash("code\n$code")
internal fun renewalAccountDigest(username:String,password:String)=managementHash("account\n${managementHash(username)}\n${managementHash(password)}")
internal fun renewalLoginSigningText(nonce:String,install:String,appPackage:String,key:String,identityDigest:String,country:String?=null)=
    if(country==null)"ECRENEWLOGIN1\n$nonce\n$install\n$appPackage\n$key\n$identityDigest" else "ECRENEWLOGIN2\n$nonce\n$install\n$appPackage\n$key\n$identityDigest\n$country"
