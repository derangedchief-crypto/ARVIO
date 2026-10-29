package com.arflix.tv.management

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Management secrets never fall back to plaintext or share profile/cache stores. */
internal class ManagementVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "device-management-v1").apply { check(isDirectory || mkdirs()) }
    private val gson = Gson()
    private val store: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun file(scope: String): AtomicFile {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(directory, "$scope.enc"))
    }
    private fun encryptionKey(create: Boolean): SecretKey {
        (store.getKey("control-management-encryption-v1", null) as? SecretKey)?.let { return it }
        check(create) { "Management encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("control-management-encryption-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(scope: String): ManagementSession? {
        val file = file(scope)
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        check(bytes.size in 29..1048576 && bytes[0] == 1.toByte()) { "Invalid management state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val decoded = String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        return gson.fromJson(decoded, ManagementSession::class.java).also {
            check(it.scope == scope && it.installationId.matches(Regex("[a-f0-9]{32}")))
            check(it.policy == null || it.policy in MANAGEMENT_POLICIES)
        }
    }
    fun save(session: ManagementSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true))
        cipher.updateAAD(session.scope.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        val target = file(session.scope)
        val output = target.startWrite()
        try { output.write(byteArrayOf(1) + cipher.iv + encrypted); target.finishWrite(output) }
        catch (error: Throwable) { target.failWrite(output); throw error }
    }
    fun publicKey(scope: String): ByteArray {
        val alias = "control-management-signing-$scope"
        val existing = store.getCertificate(alias)
        if (existing != null) return existing.publicKey.encoded
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair().public.encoded
    }
    fun existingPublicKey(scope:String):ByteArray? {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        return store.getCertificate("control-management-signing-$scope")?.publicKey?.encoded
    }
    fun sign(scope: String, text: String): String {
        val key = store.getKey("control-management-signing-$scope", null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(text.toByteArray(Charsets.UTF_8)) }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }
}
