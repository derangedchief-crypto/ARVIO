package com.arflix.tv.network

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class DnsFallbackTest {
    private val answer=listOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
    @Test fun missingPublicSuffixResourceFallsBackWithoutChangingTls() {
        assertEquals(answer,lookupDnsWithFallback(true,
            {throw IllegalStateException("Unable to load PublicSuffixDatabase.list resource.")},{answer}))
    }
    @Test fun selectedDnsFailureUsesSystemAndDoesNotFallbackTwice() {
        var calls=0
        assertEquals(answer,lookupDnsWithFallback(true,{throw UnknownHostException("fixture")},{calls++;answer}))
        assertEquals(1,calls)
        assertEquals(answer,lookupDnsWithFallback(true,{answer},{fail("Unexpected fallback");answer}))
    }
    @Test fun unrelatedBugsAndSystemDnsFailuresAreNotSwallowed() {
        assertThrows(IllegalStateException::class.java){lookupDnsWithFallback(true,{error("different failure")},{answer})}
        assertThrows(IllegalStateException::class.java){lookupDnsWithFallback(false,{error("Unable to load PublicSuffixDatabase.list resource.")},{answer})}
    }
    @Test fun failureOfBothResolversRemainsRetryableDnsError() {
        assertThrows(UnknownHostException::class.java){lookupDnsWithFallback(true,
            {error("Unable to load PublicSuffixDatabase.list resource.")},{throw UnknownHostException("offline")})}
    }
}
