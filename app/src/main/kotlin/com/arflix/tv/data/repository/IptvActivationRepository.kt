package com.arflix.tv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class IptvActivationCredentials(val username: String, val password: String)

/** Resolves a customer activation code without exposing panel database access in the app. */
@Singleton
class IptvActivationRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun resolve(code: String): Result<IptvActivationCredentials> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(ACTIVATION_URL)
                .post(JSONObject().put("activation_code", code).toString().toRequestBody(jsonMediaType))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful || json?.optBoolean("ok") != true) {
                    throw IllegalStateException(json?.optString("error").orEmpty().ifBlank {
                        "Invalid or inactive activation code."
                    })
                }
                val username = json.optString("username").trim()
                val password = json.optString("password")
                require(username.isNotBlank() && password.isNotBlank()) { "Activation response was incomplete." }
                Result.success(IptvActivationCredentials(username, password))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val ACTIVATION_URL = "https://activationworker.extremeiptv.net/activate"
    }
}
