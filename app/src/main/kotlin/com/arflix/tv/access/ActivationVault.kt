package com.arflix.tv.access

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from profile/cloud settings, private/no-backup, no plaintext fallback.
 * Existing signing keys, management credentials and trial history are untouched. */
internal class ActivationVault(context:Context,private val keyProvider:((Boolean)->SecretKey)?=null) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"verified-primary-v1.enc"))
    private fun key(create:Boolean):SecretKey {
        keyProvider?.let{return it(create)}
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("verified-primary-v1",null) as? SecretKey)?.let{return it}
        check(create){"Primary activation key unavailable"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder("verified-primary-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load():ActivationAnchor? {
        if(!file.baseFile.exists())return null
        val bytes=file.readFully();check(bytes.size in 29..8192&&bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)));updateAAD("verified-primary-v1".toByteArray())}
        val json=JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
        return ActivationAnchor(json.getString("username"),json.getString("password"))
    }
    fun save(anchor:ActivationAnchor) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key(true));updateAAD("verified-primary-v1".toByteArray())}
        val bytes=JSONObject().put("username",anchor.username).put("password",anchor.password).toString().toByteArray()
        val output=file.startWrite()
        try{output.write(byteArrayOf(1)+cipher.iv+cipher.doFinal(bytes));file.finishWrite(output)}
        catch(e:Throwable){file.failWrite(output);throw e}
    }
}
