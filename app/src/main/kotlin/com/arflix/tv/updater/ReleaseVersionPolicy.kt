package com.arflix.tv.updater

/** A cosmetic brand edit must have a distinguishable, monotonically newer update.
 * Unbranded release acceptance stays exactly three numeric components. */
internal object ReleaseVersionPolicy {
    private val regular = Regex("[vV]?\\d+(\\.\\d+){2}(-test)?")
    private val branded = Regex("[vV]?\\d+(\\.\\d+){3}(-test)?")
    fun accepts(tag: String, hasBrand: Boolean): Boolean = (if (hasBrand) branded else regular).matches(tag)
    fun brandAssetName(version: String): String? = version.takeIf { accepts(it, true) }?.let { "Brand-$it.apk" }
}
