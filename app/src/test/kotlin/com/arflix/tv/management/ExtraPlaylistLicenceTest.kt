package com.arflix.tv.management

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvPlaylistEntry
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class ExtraPlaylistLicenceTest {
    private val primary=IptvPlaylistEntry("list_1","Activation","https://tv.extremeiptv.net user pass")
    private val config=IptvConfig(m3uUrl=primary.m3uUrl,playlists=listOf(primary))
    private val device="a".repeat(32)
    @Before fun setup(){
        mockkConstructor(ManagementApi::class,ManagementVault::class)
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/policy",any(),null)}returns JSONObject().put("schema_version",1).put("enforced",true)
        every{anyConstructed<ManagementVault>().load(any())}answers{ManagementSession(firstArg(),"b".repeat(32),deviceId=device,credential="c".repeat(64))}
    }
    @After fun cleanup(){unmockkAll()}
    private fun gate()=ExtraPlaylistLicence(ApplicationProvider.getApplicationContext())
    private fun decision(id:String=device,allowed:Boolean=true)=JSONObject().put("schema_version",1).put("module","extra_playlists").put("device_id",id).put("allowed",allowed).put("valid_for_seconds",900)
    private suspend fun denied(block:suspend()->Unit){try{block();fail("Expected safe licence denial")}catch(_:ExtraPlaylistLicenceException){}}
    @Test fun authenticatedDecisionIsFreshAndRevocationIsNotCached()=runBlocking {
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),any())}returns decision()
        val licence=gate();licence.authorize(config);licence.authorize(config)
        coVerify(exactly=2){anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),"c".repeat(64))}
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),any())}returns decision(allowed=false)
        denied{licence.authorize(config)}
    }
    @Test fun networkOutageAndWrongDeviceNeverUnlock()=runBlocking {
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),any())}returns decision(id="d".repeat(32))
        denied{gate().authorize(config)}
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/policy",any(),null)}throws java.io.IOException("Offline")
        denied{gate().authorize(config)}
    }
    @Test fun secondaryAccountCannotBorrowAnotherResellerSubscription()=runBlocking {
        val foreign=primary.copy(m3uUrl="https://foreign.test/list.m3u")
        denied{gate().authorize(IptvConfig(m3uUrl=foreign.m3uUrl,playlists=listOf(foreign,primary.copy(id="list_2"))))}
        coVerify(exactly=0){anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),any())}
    }
    @Test fun missingEnrollmentFailsClosedButExplicitRolloutOffRetainsAccess()=runBlocking {
        every{anyConstructed<ManagementVault>().load(any())}returns null;denied{gate().authorize(config)}
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/policy",any(),null)}returns JSONObject().put("schema_version",1).put("enforced",false)
        gate().authorize(config)
        coVerify(exactly=0){anyConstructed<ManagementApi>().control("/api/device/addons/authorize",any(),any())}
    }
    @Test fun cancellationIsNotConvertedToAnUnlockOrConnectivityToast()=runBlocking {
        coEvery{anyConstructed<ManagementApi>().control("/api/device/addons/policy",any(),null)}throws CancellationException("Stopped")
        try{gate().authorize(config);fail("Expected cancellation")}catch(_:CancellationException){}
    }
}
