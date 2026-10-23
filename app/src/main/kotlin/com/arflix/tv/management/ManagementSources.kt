package com.arflix.tv.management

import androidx.annotation.Keep
import com.arflix.tv.data.repository.IptvConfig
import com.google.gson.Gson
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sensitive native/server context ONLY. Never put this in a receipt or logger. */
@Keep internal data class ManagedSourceSnapshot(val schema_version: Int = 1, val profile_ref: String,
    val revision: String, val config: IptvConfig) {
    override fun toString() = "ManagedSourceSnapshot([encrypted-context-only])"
}
internal fun managedSourcesRevision(secret: String, profile: String, config: IptvConfig): String {
    return managementPrivateMac(secret,"sources-v1\n$profile\n" + Gson().toJson(config.copy(sortOrder="provider")))
}
internal fun managementPrivateMac(secret: String, value: String): String {
    require(secret.matches(Regex("[a-f0-9]{64}")))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256"))
    return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
