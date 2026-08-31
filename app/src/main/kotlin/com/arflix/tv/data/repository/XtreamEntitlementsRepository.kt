package com.arflix.tv.data.repository

import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A Stremio addon the user's Xtream package grants. */
data class XtreamEntitlement(
    val id: String,
    val displayName: String,
    val manifestUrl: String
)

sealed class XtreamEntitlementsResult {

    /**
     * The backend read the line from the reseller panel successfully.
     * [revoked] is authoritative: those manifests must be removed.
     */
    data class Resolved(
        val granted: List<XtreamEntitlement>,
        val revoked: List<String>,
        val packageName: String?,
        val expiresAt: String?
    ) : XtreamEntitlementsResult()

    /**
     * The entitlement state could not be determined (panel outage, missing
     * permission, network error). Callers must NOT revoke anything on this —
     * treating "unknown" as "not granted" would uninstall a paid addon during a
     * transient failure.
     */
    data class Unresolved(val reason: String) : XtreamEntitlementsResult()
}

/**
 * Resolves Stremio addon entitlements from the Xtream reseller panel.
 *
 * The panel API tokens (X-Api-Key / X-Auth-User) can create and terminate
 * lines, so they never reach the device: the `xtream-entitlements` backend
 * function holds them, verifies the supplied credentials against
 * player_api.php, and returns only the manifest URLs this user is entitled to.
 */
@Singleton
class XtreamEntitlementsRepository @Inject constructor(
    okHttpClient: OkHttpClient
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // The Xtream gate blocks on this call, so it gets a tighter ceiling than the
    // app-wide client. newBuilder() keeps the shared connection pool.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(username: String, password: String): XtreamEntitlementsResult =
        withContext(Dispatchers.IO) {
            val endpoint = Constants.XTREAM_ENTITLEMENTS_URL
            if (!endpoint.startsWith("http")) {
                return@withContext XtreamEntitlementsResult.Unresolved("backend_not_configured")
            }
            try {
                val payload = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()
                val request = Request.Builder()
                    .url(endpoint)
                    .header("apikey", Constants.APP_ANON_KEY)
                    .header("Authorization", "Bearer ${Constants.APP_ANON_KEY}")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext XtreamEntitlementsResult.Unresolved(
                            "http_${response.code}: ${parseError(body)}"
                        )
                    }
                    val json = JSONObject(body)
                    if (!json.optBoolean("resolved", false)) {
                        return@withContext XtreamEntitlementsResult.Unresolved(
                            json.optString("reason").ifBlank { "unresolved" }
                        )
                    }
                    XtreamEntitlementsResult.Resolved(
                        granted = parseGranted(json),
                        revoked = parseRevoked(json),
                        packageName = json.optString("package_name").ifBlank { null },
                        expiresAt = json.optString("expires_at").ifBlank { null }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                XtreamEntitlementsResult.Unresolved(
                    e.message ?: e::class.simpleName ?: "unknown_error"
                )
            }
        }

    private fun parseGranted(json: JSONObject): List<XtreamEntitlement> {
        val array = json.optJSONArray("granted") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val manifestUrl = item.optString("manifest_url").trim()
            if (manifestUrl.isEmpty()) return@mapNotNull null
            XtreamEntitlement(
                id = item.optString("id").ifBlank { manifestUrl },
                displayName = item.optString("display_name").ifBlank { "Add-on" },
                manifestUrl = manifestUrl
            )
        }
    }

    private fun parseRevoked(json: JSONObject): List<String> {
        val array = json.optJSONArray("revoked") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().ifBlank { null }
        }
    }

    private fun parseError(body: String): String = try {
        JSONObject(body).optString("error").ifBlank { "request_failed" }
    } catch (e: Exception) {
        "request_failed"
    }
}
