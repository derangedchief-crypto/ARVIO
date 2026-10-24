package com.arflix.tv.updater

import org.junit.Assert.*
import org.junit.Test

class BrandUpdatePolicyTest {
    @Test fun `brand metadata uses only owner site and explicit channel`() {
        val brand = "a".repeat(32)
        assertEquals("https://control.fecxbrfzku.net/api/app-updates/$brand/testing", BrandUpdatePolicy.metadataUrl(brand, true))
        assertEquals("https://control.fecxbrfzku.net/api/app-updates/$brand/production", BrandUpdatePolicy.metadataUrl(brand, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `brand cannot inject a different metadata origin or path`() {
        BrandUpdatePolicy.metadataUrl("../other", true)
    }

    @Test fun `asset must be an exact site owned immutable version with no redirects or external links`() {
        val version = "2.0.115.3-test"
        val valid = "https://control.fecxbrfzku.net/downloads/apps/${"b".repeat(32)}/Brand-$version.apk"
        assertTrue(BrandUpdatePolicy.acceptsAsset(valid, version))
        for (invalid in listOf(
            valid.replace("https://", "http://"),
            valid.replace("control.fecxbrfzku.net", "evil.test"),
            valid.replace("control.fecxbrfzku.net", "control.fecxbrfzku.net.evil.test"),
            valid + "?redirect=elsewhere", valid + "#elsewhere",
            valid.replace("Brand-$version", "Brand-2.0.115.2-test"),
            "https://github.com/example/repo/releases/download/latest/Brand-$version.apk"
        )) assertFalse(invalid, BrandUpdatePolicy.acceptsAsset(invalid, version))
        assertFalse(BrandUpdatePolicy.acceptsAsset(valid, "../bad"))
    }
}
