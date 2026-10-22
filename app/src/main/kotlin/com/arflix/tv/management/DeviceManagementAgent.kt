package com.arflix.tv.management

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import com.arflix.tv.BuildConfig
import com.arflix.tv.data.repository.IptvRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Foreground-only management. Enrollment/outages never change IPTV auth, update
 * source, profiles or playback. Only an authenticated explicit policy may gate UI. */
@Singleton
class DeviceManagementAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: Lazy<IptvRepository>,
) {
    private val vault by lazy { ManagementVault(context) }
    private val api = ManagementApi()
    private val mutation = Mutex()
    private val runner = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DeviceManagementState())
    val state = _state.asStateFlow()
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    private class Active(val account: ManagementAccount, @Volatile var session: ManagementSession, val publicKey: ByteArray) {
        var status = "Connecting to control center"
        var heartbeat = 60
        var nextRenew = 0L
    }
    @Volatile private var active: Active? = null

    fun onInteraction() { lastInteraction = SystemClock.elapsedRealtime() }
    fun dismissMessage(id: String) {
        val current = active ?: return
        scope.launch {
            mutation.withLock {
                if (active !== current || current.session.message?.id != id) return@withLock
                val next = current.session.copy(message = null)
                current.session = next
                publish(current)
                // A storage failure must not trap the user in a provider dialog.
                try { vault.save(next) } catch (_: Exception) { /* retain in-memory dismissal */ }
            }
        }
    }
    private suspend fun change(current: Active, update: (ManagementSession) -> ManagementSession) = mutation.withLock {
        if (active !== current) return@withLock
        val proposed = update(current.session)
        val now = System.currentTimeMillis()
        val next = proposed.copy(message = proposed.message?.takeIf { it.expiresAtMs - now in 1..3600_000L })
        try { vault.save(next) }
        catch (error: Exception) {
            // A full/unwritable disk must not ignore a newly authenticated block.
            if (next.policy in setOf("suspended", "blocked", "removed")) { current.session = next; publish(current) }
            throw error
        }
        current.session = next
        publish(current)
    }
    private fun publish(current: Active) {
        if (active !== current) return
        _state.value = DeviceManagementState(false, current.session.installationId, current.status, current.session.policy,
            current.session.message?.takeIf { it.expiresAtMs - System.currentTimeMillis() in 1..3600_000L })
    }
    suspend fun runWhileStarted(): Unit = withContext(Dispatchers.IO) {
        runner.withLock {
        repository.get().observeConfig().map(::managementAccount).distinctUntilChanged()
            .retryWhen { error, _ ->
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(checkingLocalState = false, status = "Management state unavailable")
                delay(15_000); true
            }.collectLatest { account ->
                if (account == null) {
                    mutation.withLock { active = null; _state.value = DeviceManagementState(false, status = "No eligible activation account") }
                    return@collectLatest
                }
                val knownPolicy = mutation.withLock {
                    val sameAccount = active?.account?.scope == account.scope
                    val previous = _state.value
                    active = null
                    _state.value = DeviceManagementState(true, if (sameAccount) previous.installationId else "", "Checking saved device policy", if (sameAccount) previous.policy else null)
                    if (sameAccount) previous.policy else null
                }
                try {
                    val key = vault.publicKey(account.scope)
                    val fingerprint = managementHash(key)
                    var saved = vault.load(account.scope) ?: ManagementSession(account.scope, newInstallation())
                    if (knownPolicy in setOf("suspended", "blocked", "removed")) saved = saved.copy(policy = knownPolicy)
                    if (saved.keyFingerprint.isNotEmpty() && saved.keyFingerprint != fingerprint) {
                        // A lost signing key cannot silently reuse another identity.
                        // Keep known restrictions until another owner-approved enrollment.
                        saved = saved.copy(installationId = newInstallation(), credential = null, deviceId = null, requestId = null, pollToken = null, receipts = emptyList(), message = null)
                    }
                    saved = saved.copy(keyFingerprint = fingerprint)
                    vault.save(saved)
                    val current = Active(account, saved, key)
                    mutation.withLock { active = current; publish(current) }
                    var failures = 0
                    while (currentCoroutineContext().isActive) {
                        try {
                            cycle(current)
                            failures = 0
                            delay((current.heartbeat.coerceIn(15, 300) * 1000L) + Random.nextLong(0, 5000))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failures = (failures + 1).coerceAtMost(5)
                            if (error is ManagementHttpException && error.status == 401 && current.session.credential != null) {
                                change(current) { it.copy(credential = null, credentialExpiresAtMs = 0) }
                            }
                            current.status = if (current.session.policy in setOf("suspended", "blocked", "removed")) "Waiting for provider policy check" else "Control center unavailable — app activation is unchanged"
                            publish(current)
                            delay((10_000L shl (failures - 1)).coerceAtMost(300_000) + Random.nextLong(0, 3000))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // No plaintext fallback and no clearing customer/profile stores.
                    _state.value = _state.value.copy(checkingLocalState = false, status = "Management key/state unavailable")
                }
            }
        }
    }
    private suspend fun enroll(current: Active) {
        val session = current.session
        val challenge = api.control("/api/device/enrollment/challenge", JSONObject().put("schema_version", 1)
            .put("installation_id", session.installationId).put("key_fingerprint", session.keyFingerprint).put("app_package", context.packageName))
        require(challenge.getInt("schema_version") == 1)
        val nonce = challenge.getString("challenge").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val proof = api.proof(current.account, session.installationId, session.keyFingerprint, context.packageName, nonce)
        val signature = vault.sign(current.account.scope, enrollmentSigningText(nonce, proof, session.installationId, context.packageName, session.keyFingerprint))
        val result = api.control("/api/device/enrollment/register", JSONObject().put("schema_version", 1).put("challenge", nonce)
            .put("proof", proof).put("public_key", Base64.encodeToString(current.publicKey, Base64.NO_WRAP)).put("signature", signature)
            .put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(120)).put("model", Build.MODEL.take(120))
            .put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").put("app_version", BuildConfig.VERSION_NAME))
        require(result.getInt("schema_version") == 1 && result.getString("installation_id") == session.installationId)
        val id = result.getString("request_id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val token = result.getString("poll_token").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        change(current) { it.copy(requestId = id, pollToken = token) }
    }
    private fun delivery(session: ManagementSession) = JSONObject().put("schema_version", 1).put("request_id", session.requestId).put("poll_token", session.pollToken)
    private suspend fun poll(current: Active) {
        val session = current.session
        try {
            val result = api.control("/api/device/enrollment/poll", delivery(session))
            require(result.getInt("schema_version") == 1 && result.getString("request_id") == session.requestId && result.getString("installation_id") == session.installationId)
            when (result.getString("status")) {
                "pending" -> { current.status = "Awaiting provider approval"; current.heartbeat = 60 }
                "rejected" -> { current.status = "Enrollment declined — contact your provider"; current.heartbeat = 300 }
                "approved" -> {
                    val id = result.getString("device_id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
                    val token = result.getString("credential").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    val expiry = parseManagementTime(result.getString("credential_expires_at"))
                    change(current) { it.copy(deviceId = id, credential = token, credentialExpiresAtMs = expiry) }
                    confirm(current)
                }
                else -> error("Unknown enrollment status")
            }
        } catch (error: ManagementHttpException) {
            if (error.status == 401 || error.status == 409) change(current) { it.copy(requestId = null, pollToken = null) } else throw error
        }
        publish(current)
    }
    private suspend fun confirm(current: Active) {
        val session = current.session
        if (session.pollToken == null || session.requestId == null || session.credential == null) return
        try {
            val result = api.control("/api/device/enrollment/confirm", delivery(session))
            require(result.getInt("schema_version") == 1 && result.getBoolean("confirmed") && result.getString("device_id") == session.deviceId)
            change(current) { it.copy(requestId = null, pollToken = null) }
        } catch (error: ManagementHttpException) {
            if (error.status == 401 || error.status == 409) change(current) { it.copy(requestId = null, pollToken = null) } else throw error
        }
    }
    private suspend fun cycle(current: Active) {
        val session = current.session
        if (session.credential == null) {
            if (session.pollToken == null) enroll(current)
            poll(current)
            if (current.session.credential == null) return
        }
        confirm(current)
        // Renew in advance, but a temporarily unavailable proof worker must not
        // stop existing authenticated policy checks while the token is still valid.
        if (current.session.credentialExpiresAtMs > 0 && current.session.credentialExpiresAtMs - System.currentTimeMillis() < 7L * 86400_000 && SystemClock.elapsedRealtime() >= current.nextRenew) {
            current.nextRenew = SystemClock.elapsedRealtime() + 3600_000
            try { enroll(current); poll(current) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* retain usable credential */ }
        }
        val storage = StatFs(context.filesDir.absolutePath)
        val response = api.control("/api/device/check-in", JSONObject().put("schema_version", 1).put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
            .put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").put("app_version", BuildConfig.VERSION_NAME)
            .put("activity_state", if (SystemClock.elapsedRealtime() - lastInteraction >= 300_000) "idle" else "active")
            .put("capabilities", JSONArray(MANAGEMENT_CAPABILITIES)).put("storage_total", storage.totalBytes).put("storage_free", storage.availableBytes), current.session.credential)
        require(response.getInt("schema_version") == 1 && response.getString("device_id") == current.session.deviceId)
        val policy = response.getString("policy").also { require(it in MANAGEMENT_POLICIES) }
        current.heartbeat = response.optInt("heartbeat_seconds", 60).coerceIn(15, 300)
        current.status = if (policy == "active") "Connected to provider control center" else "Provider policy: $policy"
        change(current) { it.copy(policy = policy, message = if (policy == "active") it.message else null) }
        if (policy == "removed") {
            change(current) { it.copy(credential = null, requestId = null, pollToken = null) }
            return
        }
        if (policy != "active") return
        val commands = response.getJSONArray("commands")
        require(commands.length() <= 5)
        for (i in 0 until commands.length()) handle(current, commands.getJSONObject(i))
    }
    private suspend fun handle(current: Active, command: JSONObject) {
        val id = command.getString("id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val lease = command.getString("lease_token").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val prior = current.session.receipts.firstOrNull { it.id == id }
        val receipt = if (prior != null) prior else {
            val kind = command.getString("kind")
            val payload = command.optJSONObject("payload") ?: JSONObject()
            val item = ManagementCommand(id, kind, runCatching { parseManagementTime(command.getString("expires_at")) }.getOrDefault(0),
                payload.optString("message", ""), payload.optInt("duration_seconds", 0))
            var result: CommandReceipt? = null
            // Persist before acknowledgment: a retry after process/network failure
            // returns the same result and does not redisplay a dismissed message.
            change(current) { val plan = planManagementCommand(it, item, System.currentTimeMillis()); result = plan.receipt; plan.session }
            result ?: return
        }
        api.control("/api/device/commands/$id/ack", JSONObject().put("schema_version", 1).put("lease_token", lease)
            .put("status", receipt.status).put("summary", receipt.summary), current.session.credential)
    }
    private fun newInstallation() = UUID.randomUUID().toString().replace("-", "")
}

internal fun parseManagementTime(value: String): Long {
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)?.time ?: error("Invalid management timestamp")
}
