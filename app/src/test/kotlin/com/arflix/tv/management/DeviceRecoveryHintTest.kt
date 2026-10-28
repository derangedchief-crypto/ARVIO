package com.arflix.tv.management

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DeviceRecoveryHintTest {
    @Test fun rollbackOfOptionalWebsiteCapabilityRetriesTheOldSchemaOnce()=runBlocking {
        val attempts=mutableListOf<Boolean>()
        val value=optionalRecoveryCheckIn(true){hint->attempts+=hint;if(hint)throw ManagementHttpException(422) else "checked-in"}
        assertEquals("checked-in",value);assertEquals(listOf(true,false),attempts)
    }
    @Test fun authFailureNeverFallsBackAroundDeviceIdentityChecks()=runBlocking {
        var attempts=0
        try{optionalRecoveryCheckIn(true){attempts++;throw ManagementHttpException(401)};fail("Auth must propagate")}
        catch(error:ManagementHttpException){assertEquals(401,error.status)}
        assertEquals(1,attempts)
    }
    @Test fun sameScopedDeviceAndPackageProduceSameHintWithoutAnInstallSeed(){
        val first=DeviceRecoveryHint.derive("0123456789abcdef","com.arvio.tv.beta")
        val reinstalled=DeviceRecoveryHint.derive("0123456789ABCDEF","com.arvio.tv.beta")
        assertNotNull(first);assertEquals(first,reinstalled);assertEquals(64,first!!.length)
        assertFalse(first.contains("0123456789abcdef"))
    }
    @Test fun differentAndroidUsersOrPackagesNeverShareTheSameMatchingValue(){
        val first=DeviceRecoveryHint.derive("0123456789abcdef","com.arvio.tv.beta")
        assertNotEquals(first,DeviceRecoveryHint.derive("abcdef0123456789","com.arvio.tv.beta"))
        assertNotEquals(first,DeviceRecoveryHint.derive("0123456789abcdef","com.arvio.tv.test"))
        assertNotEquals(first,DeviceRecoveryHint.derive("0123456789abcdef","com.extreme.brand.b12345678901234567890123456789012"))
    }
    @Test fun unavailableAndKnownBrokenIdentifiersDisableMatchingRatherThanInventingIdentity(){
        for(value in listOf(null,"","0","unknown","0000000000000000","ffffffffffffffff","9774d56d682e549c"))
            assertNull(DeviceRecoveryHint.derive(value,"com.arvio.tv.beta"))
    }
    @Test fun oldEnrollmentSignatureProtocolIsUnchangedWithoutServerCapability(){
        assertEquals("ECENROLL1\nnonce\n${managementHash("proof")}\ninstall\npackage\nkey",
            enrollmentSigningText("nonce","proof","install","package","key"))
    }
    @Test fun newEnrollmentProtocolBindsTheProtectedHintIntoTheSignature(){
        val first=enrollmentSigningText("nonce","proof","install","package","key","hint-one")
        assertEquals("ECENROLL2\nnonce\n${managementHash("proof")}\ninstall\npackage\nkey\nhint-one",first)
        assertNotEquals(first,enrollmentSigningText("nonce","proof","install","package","key","hint-two"))
    }
    @Test fun existingKeyBackfillSignatureBindsDevicePackageAndHint(){
        assertEquals("ECRECOVERYHINT1\ndevice\npackage\nhint",DeviceRecoveryHint.signingText("device","package","hint"))
        assertNotEquals(DeviceRecoveryHint.signingText("one","package","hint"),DeviceRecoveryHint.signingText("two","package","hint"))
    }
}
