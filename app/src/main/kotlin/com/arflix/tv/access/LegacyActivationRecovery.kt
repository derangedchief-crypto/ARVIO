package com.arflix.tv.access

import android.content.Context
import android.util.Base64
import com.arflix.tv.management.ManagementAccount
import com.arflix.tv.management.ManagementSession
import com.arflix.tv.management.ManagementVault
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface LegacyRecovery {
    data object Fresh : LegacyRecovery
    data object Unresolved : LegacyRecovery
    data class Identified(val anchor: ActivationAnchor) : LegacyRecovery
}

/** Read-only bridge from 117. Source order, labels, IDs, profile selection and
 * account activity are NEVER evidence of which account originally activated it.
 * Only a single existing approved encrypted management identity can identify it.
 * Unmanaged/ambiguous legacy installations need authoritative recovery, not a guess.
 */
internal class LegacyActivationRecovery(private val context: Context) {
    suspend fun recover(): LegacyRecovery {
        val saved = context.settingsDataStore.data.first().asMap()
        val urls = mutableListOf<String>()
        var hasSources = saved.any { (key, value) -> key.name == "xtream_jellyfin_gate_completed" && value == true }
        for ((key, value) in saved) {
            val name = key.name
            if (value !is String || value.isBlank()) continue
            when {
                name.endsWith("_iptv_playlists_json") -> {
                    val rows = JSONArray(value)
                    if (rows.length() > 0) hasSources = true
                    for (i in 0 until rows.length()) urls += rows.getJSONObject(i).getString("m3uUrl")
                }
                name.endsWith("_iptv_m3u_url") -> {
                    hasSources = true
                    urls += decryptLegacyUrl(value)
                }
                name.endsWith("_iptv_stalker_portals_json") -> if (JSONArray(value).length() > 0) hasSources = true
                name.endsWith("_iptv_stalker_portal_url") -> hasSources = true
            }
        }
        val managementDirectory = java.io.File(context.noBackupFilesDir, "device-management-v1")
        val managementFiles = if (managementDirectory.exists())
            checkNotNull(managementDirectory.listFiles()) { "Saved device identity is unreadable" }
            else emptyArray()
        val managementScopes = managementFiles.mapNotNull {
            it.name.removeSuffix(".bak").removeSuffix(".enc").takeIf { scope -> scope.matches(Regex("[a-f0-9]{64}")) }
        }.distinct()
        val hadManagement = managementScopes.isNotEmpty()
        val anchors = urls.mapNotNull(::legacyProviderAnchor).distinctBy { it.identity }
        if (!hasSources && !hadManagement) return LegacyRecovery.Fresh
        if (!hadManagement) return LegacyRecovery.Unresolved
        val vault = ManagementVault(context)
        // Include historical identities even if their playlist was removed. Otherwise
        // removing the real primary could turn a remaining managed extra into primary.
        return identifyLegacyAccount(true, anchors, managementScopes.map { scope -> checkNotNull(vault.load(scope)) })
    }

    // Read the 117 format without generating/replacing any missing encryption key.
    private fun decryptLegacyUrl(raw: String): String {
        if (!raw.startsWith("encv1:")) return raw
        val parts = raw.removePrefix("encv1:").split(":", limit = 2)
        require(parts.size == 2)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("arvio_iptv_config_v1", null) as? SecretKey ?: error("Saved account key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

internal fun legacyProviderAnchor(raw: String): ActivationAnchor? {
    val parts = raw.trim().split(Regex("\\s+"), limit = 3)
    val url = (if (parts.size == 3) parts[0] else raw).toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.host != "tv.extremeiptv.net" || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val user = if (parts.size == 3) parts[1] else url.queryParameter("username") ?: return null
    val pass = if (parts.size == 3) parts[2] else url.queryParameter("password") ?: return null
    return if (user.length in 1..256 && pass.length in 1..512) ActivationAnchor(user, pass) else null
}

internal fun identifyLegacyAccount(
    hasSavedAccount: Boolean,
    candidates: List<ActivationAnchor>,
    sessions: List<ManagementSession>,
): LegacyRecovery {
    if (!hasSavedAccount) return LegacyRecovery.Fresh
    // Even an unapproved historical identity can be the original primary. Its
    // absence from the current playlist must not promote a later approved extra.
    if (sessions.map { it.scope }.distinct().size != 1) return LegacyRecovery.Unresolved
    val approved = sessions.filter { session -> session.deviceId != null &&
        session.credential != null && session.keyFingerprint.matches(Regex("[a-f0-9]{64}")) &&
        session.policy in setOf("active", "suspended", "blocked", "removed") }.distinctBy { it.scope }
    val identity = approved.singleOrNull() ?: return LegacyRecovery.Unresolved
    val matches = candidates.distinctBy { it.identity }.filter {
        ManagementAccount(it.username, it.password).scope == identity.scope
    }
    return matches.singleOrNull()?.let { LegacyRecovery.Identified(it) } ?: LegacyRecovery.Unresolved
}
