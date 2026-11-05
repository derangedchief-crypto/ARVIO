package com.extreme.myhub

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class Session(val token: String, val csrf: String, val name: String)

/** Device-bound encryption; password and activation key are never stored. */
internal class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("my-hub-session", Context.MODE_PRIVATE)
    private val alias = "my-hub-session-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(alias.toByteArray()) }
        val json = org.json.JSONObject().put("token", session.token).put("csrf", session.csrf).put("name", session.name).toString()
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("encrypted", encoded).commit()) { "Session could not be saved." }
    }
    fun load(): Session? = try {
        preferences.getString("encrypted", null)?.let {
            val raw = Base64.decode(it, Base64.NO_WRAP)
            require(raw.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD(alias.toByteArray()) }
            val json = org.json.JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size))))
            Session(json.getString("token"), json.getString("csrf"), json.getString("name")).also { require(it.token.matches(Regex("[a-f0-9]{64}"))) }
        }
    } catch (_: Exception) { clear(); null }
    fun clear() { preferences.edit().clear().commit() }
    fun savePending(value: String?) {
        if (value == null) { check(preferences.edit().remove("pending").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("$alias-pending".toByteArray()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("pending", encoded).commit())
    }
    fun pending(): String? = preferences.getString("pending", null)?.let {
        val raw = Base64.decode(it, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD("$alias-pending".toByteArray()) }
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)))
    }
}
