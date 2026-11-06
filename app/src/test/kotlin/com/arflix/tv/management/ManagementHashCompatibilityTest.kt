package com.arflix.tv.management

import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagementHashCompatibilityTest {
    @Test fun knownSha256VectorsRemainExact() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", managementHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", managementHash("abc"))
    }

    @Test fun binaryAndUtf8InputsMatchPreviouslyPersistedFormat() {
        val values = listOf(ByteArray(256) { it.toByte() }, "Customer é 水\n".toByteArray(Charsets.UTF_8))
        values.forEach { bytes ->
            val old = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { String.format(Locale.US, "%02x", it) }
            assertEquals(old, managementHash(bytes))
        }
        val originalScope = MessageDigest.getInstance("SHA-256")
            .digest("extreme-management-account-v1\ncustomer".toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
        assertEquals(originalScope, ManagementAccount(" Customer ", "unchanged").scope)
    }
}
