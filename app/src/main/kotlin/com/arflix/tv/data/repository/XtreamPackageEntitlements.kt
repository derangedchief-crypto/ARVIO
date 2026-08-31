package com.arflix.tv.data.repository

/**
 * Maps Xtream package/bouquet names to Stremio addons that should be installed
 * automatically when the user's panel package grants them.
 *
 * The panel is the single source of truth: on every successful Xtream gate login
 * the granted set is installed and the non-granted set is removed, so a package
 * downgrade takes the addon away again.
 *
 * Matching is deliberately loose — providers write package names by hand, so
 * "Cloud Stream Enabled", "cloud-stream enabled" and "CLOUD  STREAM  ENABLED"
 * all match the same keyword.
 */
object XtreamPackageEntitlements {

    data class Entitlement(
        val id: String,
        /** Lower-case, single-spaced keyword to look for in the package name. */
        val keyword: String,
        val displayName: String,
        val manifestUrl: String,
    )

    private val entitlements: List<Entitlement> = listOf(
        Entitlement(
            id = "cloud_stream",
            keyword = "cloud stream enabled",
            displayName = "Cloud Stream",
            manifestUrl = "https://torrentio.strem.fun/" +
                "qualityfilter=4k,threed|premiumize=i8hxryy94tiri9c9/manifest.json",
        ),
    )

    fun all(): List<Entitlement> = entitlements

    /**
     * Every entitlement whose keyword appears in any of [labels].
     *
     * [labels] are the raw strings harvested from the panel's player_api
     * response — see IptvRepository.collectXtreamPackageLabels().
     */
    fun match(labels: List<String>): List<Entitlement> {
        if (labels.isEmpty()) return emptyList()
        val haystack = labels.asSequence()
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toList()
        if (haystack.isEmpty()) return emptyList()
        return entitlements.filter { entitlement ->
            haystack.any { it.contains(entitlement.keyword) }
        }
    }

    /**
     * Collapses separators and whitespace so hand-typed package names still
     * match: "1 Month - 2 Connections - Cloud_Stream Enabled" -> contains
     * "cloud stream enabled".
     */
    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
