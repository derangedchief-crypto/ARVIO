package com.arflix.tv.data.repository

import android.content.Context
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class MaintenanceState(
    val active: Boolean = false,
    val message: String = "We’re performing maintenance. Please wait; the app will reopen automatically.",
    val checking: Boolean = true,
    val connectionFailed: Boolean = false,
)

/** Independent of login, profile, guide loading and playback network clients. */
@Singleton
class MaintenanceRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("hub_maintenance", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(MaintenanceState(
        active = prefs.getBoolean("active", false),
        message = prefs.getString("message", null) ?: MaintenanceState().message,
    ))
    val state = _state.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(STATUS_URL)
                    .header("Cache-Control", "no-cache, no-store").build()
                val next = client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Maintenance status unavailable" }
                    parseMaintenanceStatus(response.body?.string().orEmpty())
                }
                // A known active block survives restarts/offline operation until
                // the server explicitly returns active=false. Never touch auth.
                check(prefs.edit().putBoolean("active", next.active).putString("message", next.message).commit())
                _state.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A first-run service outage must not invent a maintenance event.
                _state.value = _state.value.copy(checking = false, connectionFailed = true)
            }
        }
    }

    companion object {
        const val STATUS_URL = "https://hubmanager.extremeiptv.net/api/maintenance"
        const val POLL_INTERVAL_MS = 10_000L
    }
}

internal fun parseMaintenanceStatus(raw: String): MaintenanceState {
    require(raw.length <= 8_192) { "Invalid maintenance response" }
    val json = JsonParser.parseString(raw).asJsonObject
    val active = json.getAsJsonPrimitive("active")
    require(active != null && active.isBoolean) { "Missing maintenance flag" }
    val message = json.get("message")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.take(500)?.takeIf { it.isNotEmpty() } ?: MaintenanceState().message
    return MaintenanceState(active = active.asBoolean, message = message, checking = false)
}
