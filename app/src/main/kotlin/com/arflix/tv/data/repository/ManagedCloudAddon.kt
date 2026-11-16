package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import java.security.MessageDigest

/** Local ownership receipt. Never exported as part of a cloud addon list. */
internal data class ManagedCloudAddon(
    val active: Addon? = null,
    val ownedIds: Set<String> = emptySet(),
    val ownedUrls: Set<String> = emptySet(),
) {
    fun owns(addon: Addon): Boolean = addon.id in ownedIds ||
        addon.url?.let { managedManifestFingerprint(it) in ownedUrls } == true

    fun protectCloud(incoming: List<Addon>, local: List<Addon>): List<Addon> {
        val installed = active?.let { saved -> local.firstOrNull { it.id == saved.id } ?: saved }
        return incoming.filterNot { owns(it) || it.id == installed?.id } + listOfNotNull(installed)
    }
}

internal fun managedManifestFingerprint(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.trim().substringBefore('#').toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

// Exact official configurations shipped before all install paths recorded ownership.
// Fingerprints avoid embedding old credentials, and do not match unrelated user addons.
internal val legacyManagedManifestFingerprints = setOf(
    "189c68e496328e8c4adbac1e7301e8cd9d3c7d87fdb8473e73e2c1f207d298a4",
    "51603814a3a47a248af8f0525f09e596611b72e4bd2655ceb4b27150e34be4a4",
)

internal fun replaceManagedCloudAddon(
    current: List<Addon>, previous: ManagedCloudAddon?, replacement: Addon?,
    configuredUrl: String, trackedId: String?,
    legacyUrls: Set<String> = legacyManagedManifestFingerprints,
): Pair<List<Addon>, ManagedCloudAddon> {
    val urls = previous?.ownedUrls.orEmpty() + legacyUrls + managedManifestFingerprint(configuredUrl)
    val ids = previous?.ownedIds.orEmpty() + listOfNotNull(trackedId, previous?.active?.id, replacement?.id)
    val receipt = ManagedCloudAddon(ownedIds = ids, ownedUrls = urls)
    val owned = current.filter(receipt::owns)
    // A URL rotation must not turn a deliberately disabled source back on.
    val old = current.firstOrNull { it.id == previous?.active?.id }
        ?: current.firstOrNull { it.id == trackedId } ?: owned.firstOrNull()
    val active = replacement?.copy(isEnabled = old?.isEnabled ?: replacement.isEnabled)
    val next = receipt.copy(active = active, ownedIds = ids + owned.map { it.id })
    return (current.filterNot(next::owns) + listOfNotNull(active)) to next
}
