package com.arflix.tv.management

import androidx.annotation.Keep
import com.arflix.tv.data.repository.IptvConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale

internal fun managementHash(value: String): String = managementHash(value.toByteArray(Charsets.UTF_8))
internal fun managementHash(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }

internal data class ManagementAccount(val username: String, val password: String) {
    val scope: String get() = managementHash("extreme-management-account-v1\n" + username.trim().lowercase(Locale.ROOT))
    override fun toString() = "ManagementAccount([redacted])"
}

/** Only the existing activated provider is eligible. Never send third-party
 * playlist credentials to the activation worker or choose a client-supplied host. */
internal fun managementAccount(config: IptvConfig): ManagementAccount? {
    val urls = if (config.playlists.isEmpty()) listOf(config.m3uUrl)
        else config.playlists.filter { it.enabled }.sortedBy { if (it.id == "list_1") 0 else 1 }.map { it.m3uUrl }
    for (raw in urls) {
        val parts = raw.trim().split(Regex("\\s+"), limit = 3)
        val url = (if (parts.size == 3) parts[0] else raw).toHttpUrlOrNull() ?: continue
        if (url.scheme != "https" || url.host != "tv.extremeiptv.net" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) continue
        val user = if (parts.size == 3) parts[1].trim() else url.queryParameter("username")?.trim().orEmpty()
        val pass = if (parts.size == 3) parts[2] else url.queryParameter("password").orEmpty()
        if (user.isNotEmpty() && pass.isNotEmpty() && user.length <= 256 && pass.length <= 512) return ManagementAccount(user, pass)
    }
    return null
}

internal fun enrollmentSigningText(nonce: String, proof: String, install: String, appPackage: String, fingerprint: String): String =
    "ECENROLL1\n$nonce\n${managementHash(proof)}\n$install\n$appPackage\n$fingerprint"

internal val MANAGEMENT_POLICIES = setOf("active", "suspended", "blocked", "removed")
internal val MANAGEMENT_CAPABILITIES = listOf("request_checkin", "show_message", "refresh_playlist", "reload_epg", "get_settings", "get_diagnostics", "set_settings", "get_categories", "set_categories", "get_category_layout", "apply_category_layout")
internal fun managementSettingsRevision(profileRef: String, accountScope: String, sortOrder: String): String =
    managementHash("ECSET1\n$profileRef\n$accountScope\n$sortOrder")

internal fun validManagedSortPayload(payload: Map<String,String>): Boolean = payload.keys == setOf("profile_ref", "expected_revision", "sort_order") &&
    payload["profile_ref"]!!.matches(Regex("[a-f0-9]{64}")) && payload["expected_revision"]!!.matches(Regex("[a-f0-9]{64}")) && payload["sort_order"] in listOf("provider", "number", "name")

@Keep
data class ManagementMessage(val id: String, val text: String, val expiresAtMs: Long)
data class DeviceManagementState(
    val checkingLocalState: Boolean = true,
    val installationId: String = "",
    val status: String = "Not connected",
    val policy: String? = null,
    val message: ManagementMessage? = null,
) {
    val restricted: Boolean get() = policy in setOf("suspended", "blocked", "removed")
}

/** Only the initial cold-start policy read may hold startup. A playlist/profile
 * emission during activation must not remove the NavHost and cancel its ViewModel. */
internal fun preparingManagementAccount(previous: DeviceManagementState, sameAccount: Boolean): DeviceManagementState =
    DeviceManagementState(previous.checkingLocalState, if (sameAccount) previous.installationId else "",
        "Checking saved device policy", if (sameAccount) previous.policy else null)

@Keep
internal data class CommandReceipt(val id: String, val status: String, val summary: String, val snapshot: Map<String,String>? = null,
    @Transient val catalog: ManagementCategoryCatalog? = null,val categoryProfileRef: String? = null,val categorySourceRef: String? = null,val categoryPage: Int = 1)
internal data class ManagementCommand(val id: String, val kind: String, val expiresAtMs: Long, val text: String = "", val durationSeconds: Int = 0) {
    override fun toString() = "ManagementCommand(id=$id, kind=$kind, payload=[redacted])"
}
internal data class ManagementCommandPlan(val session: ManagementSession, val receipt: CommandReceipt)
@Keep
internal data class ManagementSession(
    val scope: String,
    val installationId: String,
    val keyFingerprint: String = "",
    val requestId: String? = null,
    val pollToken: String? = null,
    val deviceId: String? = null,
    val credential: String? = null,
    val credentialExpiresAtMs: Long = 0,
    val policy: String? = null,
    val message: ManagementMessage? = null,
    val receipts: List<CommandReceipt> = emptyList(),
    val unmanagedUntilMs: Long = 0,
    val catalogSecret: String? = null,
) {
    override fun toString() = "ManagementSession([redacted])"
}

/** A single encrypted write commits both message state and its duplicate journal. */
internal fun planManagementCommand(session: ManagementSession, command: ManagementCommand, now: Long): ManagementCommandPlan {
    session.receipts.firstOrNull { it.id == command.id }?.let { return ManagementCommandPlan(session, it) }
    var message = session.message
    val result = when {
        command.expiresAtMs <= now || command.kind !in listOf("request_checkin", "show_message") -> CommandReceipt(command.id, "failed", "Unsupported or expired command")
        command.kind == "request_checkin" -> CommandReceipt(command.id, "succeeded", "Fresh foreground app check-in received")
        command.text.isBlank() || command.text.codePointCount(0, command.text.length) > 1000 || command.durationSeconds !in 30..3600 ->
            CommandReceipt(command.id, "failed", "Invalid provider message")
        else -> {
            message = ManagementMessage(command.id, command.text, now + command.durationSeconds * 1000L)
            CommandReceipt(command.id, "succeeded", "Message received; shown when app policy/update gates permit")
        }
    }
    return ManagementCommandPlan(session.copy(message = message, receipts = (session.receipts + result).takeLast(512)), result)
}
