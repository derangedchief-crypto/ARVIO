package com.arflix.tv.updater

/** Owner-controlled delivery; reseller inputs never choose an update origin. */
internal object BrandUpdatePolicy {
    private const val ORIGIN = "https://control.fecxbrfzku.net"

    fun metadataUrl(brand: String, testing: Boolean): String {
        require(brand.matches(Regex("[a-f0-9]{32}")))
        return "$ORIGIN/api/app-updates/$brand/${if (testing) "testing" else "production"}"
    }

    fun acceptsAsset(url: String, version: String): Boolean =
        ReleaseVersionPolicy.brandAssetName(version) != null &&
            url.matches(Regex(Regex.escape(ORIGIN) + "/downloads/apps/[a-f0-9]{32}/" +
                Regex.escape("Brand-$version.apk")))
}
