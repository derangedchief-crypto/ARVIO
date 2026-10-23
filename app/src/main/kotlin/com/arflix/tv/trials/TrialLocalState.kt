package com.arflix.tv.trials

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.arflix.tv.BuildConfig
import com.arflix.tv.util.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Software-backed keys are allowed. This is possession, not hardware attestation. */
@Singleton
class TrialClientKeys @Inject constructor(@ApplicationContext private val context: Context) {
    @Synchronized private fun entry(): KeyStore.PrivateKeyEntry {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry("public-trial-session-v1",null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder("public-trial-session-v1",KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        }.generateKeyPair()
        return ks.getEntry("public-trial-session-v1",null) as KeyStore.PrivateKeyEntry
    }
    fun publicKey(): String = Base64.encodeToString(entry().certificate.publicKey.encoded,Base64.NO_WRAP)
    fun sign(text: String): String = Base64.encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(entry().privateKey);update(text.toByteArray(Charsets.UTF_8));sign() },Base64.NO_WRAP)
    fun deviceClaim(): String {
        val androidId=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?.takeIf { it.matches(Regex("[a-fA-F0-9]{16}")) }
        val source=if(androidId!=null)"android-id-v1:"+androidId.lowercase(java.util.Locale.ROOT) else {
            val p=context.getSharedPreferences("trial_device_claim_v1",Context.MODE_PRIVATE)
            val id=p.getString("fallback",null)?:UUID.randomUUID().toString().also { check(p.edit().putString("fallback",it).commit()) }
            "installation-fallback-v1:$id"
        }
        return trialHash(source)
    }
}

internal data class PendingTrial(val session: TrialSession,val contact: TrialContact)
@Singleton
class TrialPendingStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs get()=context.getSharedPreferences("trial_pending_v1",Context.MODE_PRIVATE)
    internal fun save(p: PendingTrial) {
        val j=p.session.proof().put("brand",BuildConfig.BRAND_ID).put("operation_id",p.session.operationId).put("created_at",p.session.createdAt).put("first_name",p.contact.firstName).put("phone",p.contact.phone)
        val cipher=SecureStorage.encrypt(j.toString(),"trial-pending-aes-v1")
        check(SecureStorage.isEncrypted(cipher)) { "Secure trial recovery is unavailable. Use normal activation on this device." }
        if(!prefs.edit().putString("pending",cipher).commit()){
            prefs.edit().remove("pending").apply() // Do not let an unpersisted in-memory token become submit authority.
            error("Could not save secure trial recovery.")
        }
    }
    internal fun load(): PendingTrial? {
        val raw=prefs.getString("pending",null)?:return null
        if(!SecureStorage.isEncrypted(raw)){clear();return null}
        return runCatching {
            val j=JSONObject(SecureStorage.decrypt(raw,"trial-pending-aes-v1")?:error("Invalid pending trial"))
            val age=System.currentTimeMillis()-j.getLong("created_at");check(j.optString("brand")==BuildConfig.BRAND_ID&&age in 0..30*60_000L)
            PendingTrial(TrialSession(j.getString("token"),j.getString("nonce"),j.getString("operation_id"),j.getString("request_hash"),j.getString("signature"),j.getLong("created_at")),TrialContact.parse(j.getString("first_name"),j.getString("phone")))
        }.getOrElse { clear();null }
    }
    fun clear(){prefs.edit().remove("pending").commit()}
}
