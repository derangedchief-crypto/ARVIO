package com.arflix.tv.management

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ManagementHttpException(val status: Int) : IOException("Management HTTP $status")

/** Dedicated fixed-origin client: no shared logging interceptors, cookies or redirects. */
internal class ManagementApi {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun control(path: String, body: JSONObject, credential: String? = null): JSONObject {
        require(path.startsWith("/api/device/") && !path.contains('?'))
        return post("https://control.fecxbrfzku.net$path", body, credential,if(path=="/api/device/check-in")524288 else 65536)
    }
    suspend fun proof(account: ManagementAccount, installation: String, fingerprint: String, appPackage: String, challenge: String): String {
        val response = post("https://activationworker.extremeiptv.net/management/enrollment-proof", JSONObject()
            .put("username", account.username).put("password", account.password).put("installation_id", installation)
            .put("key_fingerprint", fingerprint).put("app_package", appPackage).put("challenge_hash", managementHash(challenge)), null)
        check(response.optBoolean("ok") && response.optInt("schema_version") == 1)
        return response.getString("proof").also { check(it.length in 64..4096 && it.startsWith("ec1.")) }
    }
    private suspend fun post(url: String, body: JSONObject, credential: String?,limit: Int = 65536): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (credential != null) header("Authorization", "Bearer $credential") }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Management connection unavailable")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw ManagementHttpException(it.code)
                        val source = it.body?.source() ?: throw IOException("Empty management response")
                        source.request(limit.toLong()+1)
                        if (source.buffer.size > limit) throw IOException("Management response too large")
                        val parsed = JSONObject(source.readUtf8())
                        if (continuation.isActive) continuation.resume(parsed)
                    }
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is ManagementHttpException) error else IOException("Invalid management response")) }
            }
        })
    }
}
