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
}
