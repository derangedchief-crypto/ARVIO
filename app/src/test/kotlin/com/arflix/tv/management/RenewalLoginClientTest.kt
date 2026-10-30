package com.arflix.tv.management

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],manifest=Config.NONE,application=android.app.Application::class)
class RenewalLoginClientTest {
    @Test fun savedExpiredAccountWithoutManagementEnrollmentCanRequestCheckoutOnly()=runTest {
        val vault=mockk<ManagementVault>(relaxed=true);val api=mockk<ManagementApi>()
        val management=mockk<ManagementVault>(relaxed=true)
        every { management.load(any()) } returns null
        every { vault.load(any()) } returns null
        every { vault.publicKey(any()) } returns byteArrayOf(1,2,3)
        every { vault.sign(any(),any()) } returns "signed"
        coEvery { api.control("/api/device/renewals/login/challenge",any(),null) } returns JSONObject().put("challenge","a".repeat(64))
        coEvery { api.control("/api/device/renewals/login/open",any(),null) } answers {
            val body=secondArg<JSONObject>()
            assertFalse(body.has("device_id"));assertFalse(body.has("credential"))
            assertEquals("fixture-user",body.getJSONObject("identity").getString("username"))
            JSONObject().put("available",true).put("checkout_url","https://control.fecxbrfzku.net/renew/"+"b".repeat(64))
        }
        val client=RenewalLoginClient(mockk(),management,api,"b".repeat(32),"com.extreme.brand.b"+"b".repeat(32)+".test",vault)
        assertTrue(client.open(JSONObject().put("username","fixture-user").put("password","fixture-pass"),"c".repeat(64)).getBoolean("available"))
        verify(exactly=1){vault.save(match{it.credential==null&&it.deviceId==null&&it.requestId==null})}
        verify(exactly=0){management.save(any())}
        coVerify(exactly=0){api.proof(any(),any(),any(),any(),any())}
    }
    @Test fun knownRestrictionsAndLostKeysCannotBeBypassedByCheckoutFallback()=runTest {
        val vault=mockk<ManagementVault>(relaxed=true);val api=mockk<ManagementApi>(relaxed=true)
        val scope="c".repeat(64);val session=ManagementSession(scope,"a".repeat(32),keyFingerprint="b".repeat(64))
        val client=RenewalLoginClient(mockk(),vault,api,"b".repeat(32),"brand.test",mockk(relaxed=true))
        for(policy in listOf("blocked","suspended","removed")){
            every { vault.load(scope) } returns session.copy(policy=policy)
            try {client.open(JSONObject().put("username","user").put("password","pass"),scope);fail("Restriction ignored")}
            catch(_:IllegalStateException){}
        }
        every { vault.load(scope) } returns session
        every { vault.existingPublicKey(scope) } returns null
        try {client.open(JSONObject().put("username","user").put("password","pass"),scope);fail("Lost key recreated")}
        catch(_:IllegalStateException){}
        verify(exactly=0){vault.publicKey(any())}
        coVerify(exactly=0){api.control(any(),any(),any())}
    }
    @Test fun genericAppCannotRequestExpiredCodeCheckout()=runTest {
        val vault=mockk<ManagementVault>();val api=mockk<ManagementApi>()
        val client=RenewalLoginClient(mockk(),vault,api,"","com.arvio.tv.test",mockk(relaxed=true))
        try {client.open(JSONObject().put("activation_code","123456"));fail("Generic checkout allowed")}
        catch(_:IllegalStateException){}
        verify { vault wasNot Called };coVerify(exactly=0){api.control(any(),any(),any())}
    }
}
