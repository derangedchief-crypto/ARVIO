package com.arflix.tv.data.repository

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.Reader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamGuard"

/** SharedPreferences (not DataStore) on purpose: this is device state, not synced profile state. */
private const val PREFS_NAME = "arvio_entitlement_guard"
private const val KEY_LAST_CHECK = "last_check_at"

/** Warm foregrounds are throttled; a cold start passes 0 and always runs. */
private const val DEFAULT_MIN_INTERVAL_MS = 30 * 60_000L

private const val CONFIG_READ_TIMEOUT_MS = 10_000L
private const val INSTALL_TIMEOUT_MS = 15_000L
private const val REMOVE_TIMEOUT_MS = 5_000L

/** Category payloads are a few KB; this only exists so a hostile host cannot stream forever. */
private const val MAX_RESPONSE_CHARS = 2_000_000

/**
 * Re-checks the Xtream line's package on every app open and syncs entitlement
 * addons to it, so a marker category removed on the panel actually takes the
 * addon away instead of only being noticed at the next sign-in.
 *
 * Deliberately silent: no UI, no toast, no progress text. Failures are logged
 * and change nothing.
 *
 * Revocation only happens on a *positive* read of the provider's category list.
 * A dead network, an expired line, an HTTP error or an empty category list all
 * leave installed addons alone — treating "unknown" as "not entitled" would
 * uninstall a paid addon every time the panel hiccups or the TV boots offline.
 */
@Singleton
class XtreamEntitlementsGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val iptvRepository: IptvRepository,
    private val entitlementsRepository: XtreamEntitlementsRepository,
    // Lazy: StreamRepository is a heavy singleton and most app opens resolve to
    // "nothing changed", so it should not be built on the cold-start path.
    private val streamRepository: Lazy<StreamRepository>
) {

    /** Short timeouts: this is a background check, never worth holding resources for. */
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Main-thread only (ActivityLifecycleCallbacks are always dispatched there). */
    private var startedActivities = 0

    private data class Credentials(
        val baseUrl: String,
        val username: String,
        val password: String
    )

    /**
     * Adds the warm-foreground trigger. Call once from Application.onCreate().
     * Cold start is covered separately by [scheduleCheck] so the very first
     * check is never throttled away by the previous session's timestamp.
     */
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) {
                    scheduleCheck(reason = "foreground", initialDelayMs = 1_500L)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities -= 1
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Fire-and-forget. Never throws into the caller. */
    fun scheduleCheck(
        reason: String,
        initialDelayMs: Long = 0L,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
    ) {
        scope.launch {
            if (initialDelayMs > 0L) delay(initialDelayMs)
            runCatching { enforce(reason, minIntervalMs) }
                .onFailure { error -> Log.w(TAG, "check failed ($reason): ${error.message}") }
        }
    }

    private suspend fun enforce(reason: String, minIntervalMs: Long) = mutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        // The `in 0 until` guard also covers a clock that jumped backwards.
        if (minIntervalMs > 0L && last != 0L && (now - last) in 0L until minIntervalMs) {
            return@withLock
        }

        val credentials = readCredentials()
        if (credentials == null) {
            Log.i(TAG, "no Xtream playlist configured; skipping ($reason)")
            return@withLock
        }

        val login = iptvRepository.verifyXtreamLogin(
            credentials.baseUrl,
            credentials.username,
            credentials.password
        )
        if (!login.success) {
            // Could be an expired line, could be a dead DNS lookup. Not
            // distinguishable here, so nothing is revoked.
            Log.i(TAG, "login check not conclusive ($reason): ${login.message}; addons unchanged")
            return@withLock
        }

        val categories = fetchCategoryNames(credentials)
        if (categories.isEmpty()) {
            Log.i(TAG, "no categories returned ($reason); addons unchanged")
            return@withLock
        }

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        when (val lookup = entitlementsRepository.resolve(categories + login.packageLabels)) {
            is XtreamEntitlementsResult.Unresolved ->
                Log.i(TAG, "unresolved (${lookup.reason}) ($reason); addons unchanged")

            is XtreamEntitlementsResult.Resolved -> {
                Log.i(
                    TAG,
                    "$reason: labels=${lookup.labelCount} match=${lookup.matchedLabel} " +
                        "granted=${lookup.granted.size} revoked=${lookup.revoked.size}"
                )
                applyResolved(lookup)
            }
        }
    }

    private suspend fun applyResolved(resolved: XtreamEntitlementsResult.Resolved) {
        if (resolved.granted.isEmpty() && resolved.revoked.isEmpty()) return

        val repository = streamRepository.get()

        if (resolved.granted.isNotEmpty()) {
            // ensureCustomAddons (not addCustomAddon) so a user who deliberately
            // removed an addon does not get it silently reinstalled on reopen.
            val results = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                repository.ensureCustomAddons(resolved.granted.map { it.manifestUrl })
            }
            if (results == null) {
                Log.w(TAG, "addon install timed out")
            } else {
                results.forEach { result ->
                    result.onSuccess { addon ->
                        Log.i(TAG, "addon ready: ${addon.name} (${addon.id})")
                    }.onFailure { error ->
                        Log.w(TAG, "addon install failed: ${error.message}")
                    }
                }
            }
        }

        if (resolved.revoked.isNotEmpty()) {
            withTimeoutOrNull(REMOVE_TIMEOUT_MS) {
                repository.removeCustomAddonsByUrl(resolved.revoked)
            } ?: Log.w(TAG, "addon removal timed out")
        }
    }

    /**
     * IPTV config is profile-scoped, and observeConfig() waits on the active
     * profile id, which is still being restored during cold start — hence the
     * timeout instead of an unbounded first().
     */
    private suspend fun readCredentials(): Credentials? {
        val config = withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) {
            iptvRepository.observeConfig().first()
        } ?: return null

        val candidates = buildList {
            config.playlists.filter { it.enabled }.forEach { add(it.m3uUrl) }
            add(config.m3uUrl)
        }
        return candidates.asSequence()
            .mapNotNull { parseCredentials(it) }
            .firstOrNull()
    }

    /**
     * Handles both stored forms: the normalized get.php URL savePlaylists()
     * writes, and the raw "host user pass" triplet.
     */
    private fun parseCredentials(raw: String): Credentials? {
        val value = raw.trim()
        if (value.isBlank()) return null

        val url = value.toHttpUrlOrNull()
        if (url != null) {
            val username = url.queryParameter("username")?.trim().orEmpty()
            val password = url.queryParameter("password")?.trim().orEmpty()
            if (username.isEmpty() || password.isEmpty()) return null
            val isDefaultPort = (url.scheme == "https" && url.port == 443) ||
                (url.scheme == "http" && url.port == 80)
            val authority = if (isDefaultPort) url.host else "${url.host}:${url.port}"
            return Credentials("${url.scheme}://$authority", username, password)
        }

        val parts = value.split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val host = parts[0].trimEnd('/')
        val base = if (host.contains("://")) host else "https://$host"
        return Credentials(base, parts[1].trim(), parts[2].trim())
    }

    /**
     * Live categories are the required signal — if that call fails the whole
     * check is abandoned. VOD/series are additive so the marker can also live
     * in a movie or series category if you prefer it hidden from Live TV.
     */
    private fun fetchCategoryNames(credentials: Credentials): List<String> {
        val live = requestCategories(credentials, "get_live_categories") ?: return emptyList()
        val names = LinkedHashSet<String>(live)
        requestCategories(credentials, "get_vod_categories")?.let { names += it }
        requestCategories(credentials, "get_series_categories")?.let { names += it }
        return names.toList()
    }

    /** Null means "the call failed", empty list means "the panel returned nothing". */
    private fun requestCategories(credentials: Credentials, action: String): List<String>? {
        val url = "${credentials.baseUrl}/player_api.php".toHttpUrlOrNull()
            ?.newBuilder()
            // addQueryParameter percent-encodes; identical output for normal
            // credentials, and safe for a password containing & or =.
            ?.addQueryParameter("username", credentials.username)
            ?.addQueryParameter("password", credentials.password)
            ?.addQueryParameter("action", action)
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                parseCategoryNames(readLimited(body.charStream()))
            }
        }.getOrNull()
    }

    private fun parseCategoryNames(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val names = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("category_name")?.trim().orEmpty()
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    private fun readLimited(reader: Reader): String {
        val buffer = CharArray(8 * 1024)
        val builder = StringBuilder()
        reader.use { source ->
            while (builder.length < MAX_RESPONSE_CHARS) {
                val read = source.read(buffer)
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
        }
        return builder.toString()
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
