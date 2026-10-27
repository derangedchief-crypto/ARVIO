package com.arflix.tv.updater

/** Select the app/channel identity BEFORE ABI selection. Historical branded APKs
 * may remain on a rolling release, but are never generic testing updates. */
internal object UpdateAssetPolicy {
    fun candidates(assets:List<GitHubAssetDto>,channel:String,version:String,branded:Boolean):List<GitHubAssetDto> {
        val expected=when {
            branded -> ReleaseVersionPolicy.brandAssetName(version)
            !ReleaseVersionPolicy.accepts(version,false) -> null
            channel=="staging-testing" -> "ExtremeTV-Test-latest.apk"
            channel.isBlank()&&!version.endsWith("-test") -> "ExtremeTV-${version.removePrefix("v").removePrefix("V")}.apk"
            else -> null
        } ?: return emptyList()
        return assets.filter{it.name==expected}
    }
}
