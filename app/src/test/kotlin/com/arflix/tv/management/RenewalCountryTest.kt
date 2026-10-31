package com.arflix.tv.management

import com.arflix.tv.access.PrimaryAccessRepository
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
@Config(sdk=[28],application=android.app.Application::class,manifest=Config.NONE)
class RenewalCountryTest {
    private val primary=mockk<PrimaryAccessRepository>(relaxed=true)
    private val login=mockk<RenewalLoginClient>()
    private val api=mockk<ManagementApi>(relaxed=true)
    private fun repo()=RenewalCheckoutRepository(mockk(),primary,login,api,"b".repeat(32))
    private fun response(country:String,gateway:String)=JSONObject().put("available",true)
        .put("checkout_url","https://control.fecxbrfzku.net/renew/"+"a".repeat(64)).put("session_token","a".repeat(64))
        .put("expires_in",900).put("simulated",false).put("country",country).put("gateway",gateway)
    @Test fun boundExpiredAccountRequiresCountryBeforeAnyCheckoutRequestOrQr()=runTest {
        val identity=Triple("c".repeat(64),"d".repeat(64),1L)
        every{primary.renewalIdentity()}returns identity
        every{primary.renewalAccount()}returns ManagementAccount("fixture-user","fixture-pass")
        coEvery{login.open(any(),identity.first,"CA")}returns response("CA","square")
        val r=repo();r.load()
        assertTrue(r.state.value.needsCountry);assertNull(r.state.value.url)
        coVerify(exactly=0){login.open(any(),any(),any())}
        r.chooseCountry("CA")
        assertFalse(r.state.value.needsCountry);assertEquals("CA",r.state.value.country);assertEquals("square",r.state.value.gateway)
        assertNotNull(r.state.value.url);verify(exactly=0){primary.retry()}
    }
    @Test fun expiredCodeRequiresCountryAndRefreshKeepsTheConfirmedCountry()=runTest {
        coEvery{login.open(any(),null,"US")}answers {
            assertEquals("123456",firstArg<JSONObject>().getString("activation_code"));response("US","stripe")
        }
        val r=repo();r.loadCode("123456")
        assertTrue(r.state.value.needsCountry);assertNull(r.state.value.url)
        coVerify(exactly=0){login.open(any(),any(),any())}
        r.chooseCountry("US");assertEquals("stripe",r.state.value.gateway)
        r.refresh();coVerify(exactly=2){login.open(any(),null,"US")}
        r.chooseCountry(null);assertTrue(r.state.value.needsCountry);assertNull(r.state.value.url)
    }
    @Test fun CanadianCustomerCanNeverReceiveRampexFallbackQr()=runTest {
        coEvery{login.open(any(),null,"CA")}returns response("CA","rampex")
        val r=repo();r.loadCode("123456");r.chooseCountry("CA")
        assertNull(r.state.value.url);assertNotNull(r.state.value.message)
        assertFalse(validRenewalGateway("CA","rampex"));assertFalse(validRenewalGateway("GB","stripe"))
        assertTrue(validRenewalGateway("CA","stripe"));assertTrue(validRenewalGateway("CA","square"))
    }
}
