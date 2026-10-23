package com.arflix.tv.updater

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionPolicyTest {
    @Test fun `branded rolling channel uses only its exact immutable version asset`() {
        assertEquals("Brand-2.0.114.2-test.apk",ReleaseVersionPolicy.brandAssetName("2.0.114.2-test"))
        assertNull(ReleaseVersionPolicy.brandAssetName("2.0.114-test"))
        assertNull(ReleaseVersionPolicy.brandAssetName("../other.apk"))
    }
    @Test fun `regular releases never accept a branded revision`() {
        assertTrue(ReleaseVersionPolicy.accepts("2.0.114-test", false))
        assertFalse(ReleaseVersionPolicy.accepts("2.0.114.1-test", false))
    }
    @Test fun `brand update requires its own version identity not a generic fallback`() {
        assertTrue(ReleaseVersionPolicy.accepts("2.0.114.2-test", true))
        assertFalse(ReleaseVersionPolicy.accepts("2.0.114-test", true))
        assertFalse(ReleaseVersionPolicy.accepts("brand-latest", true))
    }
    @Test fun `brand only revision and core updates compare monotonically`() {
        assertTrue(VersionUtils.isRemoteNewer("2.0.114.2-test", "2.0.114.1-test"))
        assertTrue(VersionUtils.isRemoteNewer("2.0.115.1", "2.0.114.99"))
        assertFalse(VersionUtils.isRemoteNewer("2.0.114.1", "2.0.114.2"))
    }
}
