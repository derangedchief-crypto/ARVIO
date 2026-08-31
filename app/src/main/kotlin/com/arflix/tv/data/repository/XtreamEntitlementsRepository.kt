package com.arflix.tv.data.repository

import android.util.Log
import com.arflix.tv.util.Constants
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
     * Entitlement state was determined from real provider data.
     * [revoked] is authoritative: those manifests must be removed.
     */
    data class Resolved(
        val granted: List<XtreamEntitlement>,
        val revoked: List<String>,
        val matchedLabel: String?,
        val labelCount: Int
    ) : XtreamEntitlementsResult()

    /**
     * State could not be determined (nothing configured, or the provider
     * returned no categories at all). Callers must NOT revoke anything on this —
     * treating "unknown" as "not granted" would uninstall a paid addon after a
     * transient provider failure.
     */
    data class Unresolved(val reason: String) : XtreamEntitlementsResult()
}

private const val TAG = "XtreamEntitlements"

/** Bound on harvested labels so a huge provider cannot balloon memory. */
private const val MAX_LABELS = 4_000

/**
 * Decides which Stremio addons an Xtream account's package grants.
 *
 * Deliberately does no I/O of its own. Every input it needs is already fetched
 * by the normal sign-in flow:
 *
 * - the live/VOD/series category names the provider returned for THIS line
 *   (`IptvRepository.fetchXtreamLiveChannels` calls `get_live_categories` and
 *   stores each category name as `IptvChannel.group`, so `IptvSnapshot.grouped`
 *   is already keyed by them), and
 * - every string field from the `player_api.php` login response
 *   (`XtreamLoginCheckResult.packageLabels`).
 *
 * Those category lists are filtered by the line's bouquets server-side, which is
 * what makes a marker category ("Cloud Stream Enabled") an unforgeable
 * entitlement signal: the client cannot invent it, and no privileged panel token
 * has to ship in the APK.
 */
@Singleton
class XtreamEntitlementsRepository @Inject constructor() {

    private data class Definition(
        val id: String,
        val keyword: String,
        val displayName: String,
        val manifestUrl: String
    )

    /**
     * Matches [labels] against the configured entitlements.
     *
     * @param labels provider-supplied names — category/group names plus login
     *   response strings. Order and duplicates do not matter.
     */
    fun resolve(labels: List<String>): XtreamEntitlementsResult {
        val definitions = definitions()
        if (definitions.isEmpty()) {
            // No manifest URL configured: nothing to grant and nothing to take
            // away. Unresolved on purpose so the caller leaves addons alone.
            return XtreamEntitlementsResult.Unresolved("no_entitlements_configured")
        }

        val normalized = labels.asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LABELS)
            .toList()
        if (normalized.isEmpty()) {
            return XtreamEntitlementsResult.Unresolved("no_labels")
        }

        val granted = mutableListOf<XtreamEntitlement>()
        var matchedLabel: String? = null
        for (definition in definitions) {
            if (definition.keyword.isEmpty()) continue
            val hit = normalized.firstOrNull { it.contains(definition.keyword) } ?: continue
            if (matchedLabel == null) matchedLabel = hit
            granted += XtreamEntitlement(
                id = definition.id,
                displayName = definition.displayName,
                manifestUrl = definition.manifestUrl
            )
        }

        val grantedUrls = granted.map { it.manifestUrl }
        val revoked = definitions.map { it.manifestUrl }.filterNot { it in grantedUrls }

        Log.i(
            TAG,
            "resolved from ${normalized.size} labels: granted=${granted.size} " +
                "revoked=${revoked.size} match=$matchedLabel"
        )
        return XtreamEntitlementsResult.Resolved(
            granted = granted,
            revoked = revoked,
            matchedLabel = matchedLabel,
            labelCount = normalized.size
        )
    }

    /**
     * The addons this build knows about. Manifest URLs come from
     * secrets.properties rather than committed source because the Torrentio URL
     * embeds a Premiumize API key. An entry with no configured URL is dropped,
     * which is what makes an unconfigured checkout resolve to nothing instead of
     * revoking.
     */
    private fun definitions(): List<Definition> = listOfNotNull(
        Constants.ENTITLEMENT_CLOUD_STREAM_MANIFEST_URL
            .takeIf { it.isNotBlank() }
            ?.let { manifestUrl ->
                Definition(
                    id = "cloud_stream",
                    keyword = normalize(Constants.ENTITLEMENT_CLOUD_STREAM_KEYWORD),
                    displayName = "Cloud Stream",
                    manifestUrl = manifestUrl
                )
            }
    )

    /**
     * Collapses case and separators so a hand-typed panel name still matches:
     * "USA ⁃ Cloud_Stream Enabled | FHD" normalizes to a string containing
     * "cloud stream".
     */
    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace(SEPARATORS, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        private val SEPARATORS = Regex("[_\\-/|+.,:;()\\[\\]]+")
        private val WHITESPACE = Regex("\\s+")
    }
}
