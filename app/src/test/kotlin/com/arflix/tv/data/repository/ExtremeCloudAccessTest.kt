package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import org.junit.Assert.*
import org.junit.Test

class ExtremeCloudAccessTest {
    private val manifest = "https://cloud.example.test/auth-key/manifest.json"
    private val entitled = Addon("granted-source", "Cloud Stream", "1", "", true,
        type = AddonType.CUSTOM, url = manifest)
    @Test fun loginInstalledEntitlementManifestGrantsAction() {
        assertTrue(hasEntitledExtremeCloudSource(listOf(entitled), manifest, null))
    }
    @Test fun guardTrackedIdentitySupportsRotatedManifestUrl() {
        assertTrue(hasEntitledExtremeCloudSource(listOf(entitled.copy(url = "https://new.example.test/manifest.json")), manifest, entitled.id))
    }
    @Test fun arbitraryScraperOrSameDisplayNameDoesNotGrantCloud() {
        assertFalse(hasEntitledExtremeCloudSource(listOf(entitled.copy(id = "other", url = "https://personal.example.test/manifest.json")), manifest, null))
    }
    @Test fun disabledAndUninstalledSourcesDoNotGrantAccess() {
        assertFalse(hasEntitledExtremeCloudSource(listOf(entitled.copy(isEnabled = false)), manifest, entitled.id))
        assertFalse(hasEntitledExtremeCloudSource(listOf(entitled.copy(isInstalled = false)), manifest, entitled.id))
    }
    @Test fun revokedOrStaleGrantWithoutInstalledSourceDeniesAccess() {
        assertFalse(hasEntitledExtremeCloudSource(emptyList(), manifest, entitled.id))
    }
    @Test fun differentCredentialsInSourceUrlDoNotMatchGrant() {
        assertFalse(hasEntitledExtremeCloudSource(listOf(entitled.copy(url = "https://cloud.example.test/wrong-key/manifest.json")), manifest, null))
    }
    @Test fun noConfiguredOrTrackedEntitlementDeniesAccess() {
        assertFalse(hasEntitledExtremeCloudSource(listOf(entitled), "", null))
    }
    @Test fun stremioSchemeUsesSameEntitlementUrlIdentity() {
        assertTrue(hasEntitledExtremeCloudSource(listOf(entitled.copy(url = "stremio://cloud.example.test/auth-key/manifest.json#ignored")), manifest, null))
    }
    @Test fun genericVodFailureDoesNotAdvertiseCloudAccess() {
        assertFalse(VodVerificationException().message.orEmpty().contains("Extreme Cloud"))
    }
}
