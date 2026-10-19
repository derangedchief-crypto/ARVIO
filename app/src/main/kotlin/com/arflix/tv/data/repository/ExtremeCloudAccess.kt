package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Reuse the Cloud Stream addon installed/revoked by the Xtream entitlement flow. */
internal fun hasEntitledExtremeCloudSource(addons: List<Addon>, manifestUrl: String, trackedAddonId: String?): Boolean {
    fun canonical(raw: String?): String? {
        var url = raw?.trim().orEmpty()
        if (url.isBlank()) return null
        if (url.startsWith("stremio://", true)) url = url.substringAfter("://")
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) url = "https://$url"
        return url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
    }
    val configured = canonical(manifestUrl)
    return addons.any { addon ->
        addon.isInstalled && addon.isEnabled &&
            ((!trackedAddonId.isNullOrBlank() && addon.id == trackedAddonId) ||
                (configured != null && canonical(addon.url) == configured))
    }
}
