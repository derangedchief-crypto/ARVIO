package com.extreme.myhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class HubFailure(val status: Int, override val message: String) : Exception(message)

internal class HubClient(
    private val origin: HttpUrl = "https://control.fecxbrfzku.net/".toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
) {
    suspend fun download(path: String, session: Session, output: java.io.OutputStream, sha256: String? = null) = withContext(Dispatchers.IO) {
        val apk = Regex("/downloads/apps/[a-f0-9]{32}/Brand-[0-9.]+(-test)?\\.apk").matches(path)
        require(apk || Regex("devices/[a-f0-9]{32}/support/[a-f0-9]{32}/download|app-brand/builds/[a-f0-9]{32}/inputs").matches(path))
        require(!apk || sha256?.matches(Regex("[a-f0-9]{64}")) == true)
        val url = origin.newBuilder().encodedPath(if (apk) path else "/api/my-hub/manage/$path").build()
        val request = Request.Builder().url(url).apply { if (!apk) header("Authorization", "Bearer ${session.token}") }.build()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        http.newBuilder().callTimeout(5, TimeUnit.MINUTES).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HubFailure(response.code, "Download unavailable. Refresh and check access.")
            val body = response.body ?: throw HubFailure(503, "Empty download.")
            body.byteStream().use { input ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > (if (apk) 512L else 16L) * 1024 * 1024) throw HubFailure(503, "Download exceeds size limit."); digest.update(buffer, 0, count); output.write(buffer, 0, count) }
            }
            if (sha256 != null && digest.digest().joinToString("") { "%02x".format(it) } != sha256) throw HubFailure(503, "Download integrity check failed.")
        }
    }
    suspend fun login(username: String, password: String): Session {
        val reply = request("login", null, JSONObject().put("username", username.trim()).put("password", password))
        val user = reply.getJSONObject("user")
        require(user.getString("role") == "reseller")
        return Session(reply.getString("token"), reply.getString("csrf"), user.getString("display_name")).also {
            require(it.token.matches(Regex("[a-f0-9]{64}")) && it.csrf.matches(Regex("[a-f0-9]{64}")))
        }
    }
    suspend fun request(path: String, session: Session?, body: JSONObject? = null, query: Map<String, String> = emptyMap(), idempotency: String? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.matches(Regex("[a-z0-9/-]+")) && !path.contains("//"))
        val url = origin.newBuilder().encodedPath("/api/my-hub/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
        session?.let { request.header("Authorization", "Bearer ${it.token}") }
        if (body != null) {
            request.header("Origin", origin.toString().removeSuffix("/"))
            session?.let { request.header("X-CSRF-Token", it.csrf) }
            request.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        idempotency?.let { require(it.matches(Regex("[a-f0-9]{32}"))); request.header("Idempotency-Key", it) }
        http.newCall(request.build()).execute().use { response ->
            val source = response.body?.source() ?: throw HubFailure(503, "Empty server response.")
            source.request(4L * 1024 * 1024 + 1)
            if (source.buffer.size > 4L * 1024 * 1024) throw HubFailure(503, "Server response is too large.")
            val text = source.readUtf8()
            val json = try { JSONObject(text) } catch (_: Exception) { throw HubFailure(response.code, "Hub returned an invalid response.") }
            if (!response.isSuccessful) throw HubFailure(response.code, json.optString("error", "Request could not be completed.").take(400))
            json
        }
    }
}
