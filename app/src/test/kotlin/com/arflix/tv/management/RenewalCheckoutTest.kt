package com.arflix.tv.management

import org.junit.Assert.*
import org.junit.Test

class RenewalCheckoutTest {
    @Test fun checkoutRequiresBrandAndExactFixedOriginOpaqueToken() {
        val token="a".repeat(64)
        val url="https://control.fecxbrfzku.net/renew/$token"
        assertTrue(validRenewalCheckout(url,token,"b".repeat(32)))
        assertFalse(validRenewalCheckout(url,token,""))
        for(wrong in listOf(url.replace("https:","http:"),"$url?line=999","$url/",url.replace("control.","control.evil."),url.replace("/renew/","/api/device/")))
            assertFalse(validRenewalCheckout(wrong,token,"b".repeat(32)))
        assertFalse(validRenewalCheckout(url,"B".repeat(64),"b".repeat(32)))
    }
    @Test fun checkoutSignatureCannotBeReusedAsAnEnrollmentOrAnotherLine() {
        val text=renewalSigningText("nonce","device","package","key","primary")
        assertEquals("ECRENEWCHECKOUT1\nnonce\ndevice\npackage\nkey\nprimary",text)
        assertNotEquals(text,renewalSigningText("nonce","device","package","key","another-primary"))
        assertNotEquals(text,renewalSigningText("nonce","device","other-package","key","primary"))
    }
    @Test fun expiredLoginSignatureBindsCodeAccountInstallationBrandAndKeyWithoutRawSecrets() {
        val digest=renewalCodeDigest("123456")
        assertEquals("c9621e3692279fb32c54f841fa993e72d62c601bfd4e702c825b8d2e7f46ac8c",digest)
        val text=renewalLoginSigningText("nonce","install","brand","key",digest)
        assertFalse(text.contains("123456"));assertTrue(text.startsWith("ECRENEWLOGIN1\n"))
        assertNotEquals(text,renewalLoginSigningText("nonce","install","other","key",digest))
        assertNotEquals(text,renewalLoginSigningText("nonce","different","brand","key",digest))
        assertNotEquals(text,renewalLoginSigningText("nonce","install","brand","other",digest))
        assertNotEquals(digest,renewalCodeDigest("654321"))
        assertNotEquals(renewalAccountDigest("a\nb","c"),renewalAccountDigest("a","b\nc"))
        assertNotEquals(digest,renewalAccountDigest("123456",""))
    }
    @Test fun errorsOfferActionableSafeReasonsAndNeverEchoServerBodies() {
        assertTrue(renewalFailureMessage(429).contains("ten minutes"))
        assertTrue(renewalFailureMessage(403).contains("not eligible"))
        assertTrue(renewalFailureMessage(null).contains("retry"))
    }
}
