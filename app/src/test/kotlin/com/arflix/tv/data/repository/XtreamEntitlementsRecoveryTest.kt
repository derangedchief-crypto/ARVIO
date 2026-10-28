package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=com.arflix.tv.SentryRegressionTestApplication::class,manifest=Config.NONE)
class XtreamEntitlementsRecoveryTest {
    private val url="https://cloud.fixture.test/manifest.json"
    private val grant=XtreamEntitlement("cloud_stream","Cloud",url)
    private val resolved=XtreamEntitlementsResult.Resolved(listOf(grant),emptyList(),"cloud",1)
    private val addon=Addon("existing-cloud","Cloud","1","Fixture",true,type=AddonType.CUSTOM,url=url)
    private val streams=mockk<StreamRepository>(relaxed=true)
    private val iptv=mockk<IptvRepository>(relaxed=true)
    private val entitlements=mockk<XtreamEntitlementsRepository>(relaxed=true)
    private val config=IptvConfig(m3uUrl="https://tv.extremeiptv.net primary secret")
    private val context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("arvio_entitlement_guard",0)
    private fun guard():XtreamEntitlementsGuard {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""[{"category_id":"1","category_name":"Cloud Stream Enabled"}]""".toResponseBody()).build()
        }.build()
        return XtreamEntitlementsGuard(context,client,iptv,entitlements,Lazy{streams})
    }
    @Before fun setup(){
        prefs.edit().clear().putString("granted_addon_id:cloud_stream",addon.id)
            .putStringSet("tracked_definition_ids",setOf("cloud_stream")).commit()
        every{iptv.observeActivationConfig()}returns flowOf(config)
        coEvery{iptv.verifyXtreamLogin(any(),any(),any())}returns XtreamLoginCheckResult(true)
        every{entitlements.markerCategoryNames(any())}returns emptyList()
        every{entitlements.resolve(any())}returns resolved
        coEvery{streams.ensureCustomAddons(listOf(url))}returns listOf(Result.success(addon))
    }
    @After fun cleanup(){unmockkAll()}
    @Test fun normalRefreshPreservesExistingAddonWithoutRemovalOrReinstallation()=runBlocking {
        assertTrue(guard().applyResolved(resolved))
        assertEquals(addon.id,prefs.getString("granted_addon_id:cloud_stream",null))
        coVerify(exactly=0){streams.removeAddon(any())}
        coVerify(exactly=1){streams.ensureCustomAddons(listOf(url))}
    }
    @Test fun failedManifestRefreshKeepsWorkingCloudAndDoesNotThrottleRecovery()=runBlocking {
        coEvery{streams.ensureCustomAddons(listOf(url))}returns listOf(Result.failure(java.io.IOException("Offline")))
        val guard=guard()
        assertFalse(guard.enforce("fixture",0))
        assertEquals(addon.id,prefs.getString("granted_addon_id:cloud_stream",null))
        assertEquals(0L,prefs.getLong("last_check_at",0L))
        coVerify(exactly=0){streams.removeAddon(any())}
        coEvery{streams.ensureCustomAddons(listOf(url))}returns listOf(Result.success(addon))
        assertTrue(guard.enforce("retry",30*60_000L))
        assertTrue(prefs.getLong("last_check_at",0L)>0L)
    }
    @Test fun replacementIsInstalledAndTrackedBeforeTheOldIdentityIsRemoved()=runBlocking {
        val replacement=addon.copy(id="new-cloud")
        coEvery{streams.ensureCustomAddons(listOf(url))}coAnswers{
            assertEquals(addon.id,prefs.getString("granted_addon_id:cloud_stream",null))
            listOf(Result.success(replacement))
        }
        coEvery{streams.removeAddon(addon.id)}coAnswers{
            assertEquals(replacement.id,prefs.getString("granted_addon_id:cloud_stream",null))
            Unit
        }
        assertTrue(guard().applyResolved(resolved))
        coVerify(exactly=1){streams.removeAddon(addon.id)}
    }
    @Test fun confirmedRevocationStillRemovesTheTrackedAddon()=runBlocking {
        assertTrue(guard().applyResolved(XtreamEntitlementsResult.Resolved(emptyList(),listOf(url),null,1)))
        assertNull(prefs.getString("granted_addon_id:cloud_stream",null))
        coVerify(exactly=1){streams.removeAddon(addon.id)}
        coVerify(exactly=0){streams.ensureCustomAddons(any())}
    }
    @Test fun failedRetirementIsRetriedWithoutLosingTheReplacement()=runBlocking {
        val replacement=addon.copy(id="new-cloud")
        coEvery{streams.ensureCustomAddons(any())}returns listOf(Result.success(replacement))
        coEvery{streams.removeAddon(addon.id)}throws java.io.IOException("Temporary storage failure")
        val guard=guard()
        try{guard.applyResolved(resolved);fail("Fixture must exercise cleanup failure")}catch(_:java.io.IOException){}
        assertEquals(replacement.id,prefs.getString("granted_addon_id:cloud_stream",null))
        assertTrue(prefs.getStringSet("retired_grant_addon_ids",emptySet()).orEmpty().contains(addon.id))
        coEvery{streams.removeAddon(addon.id)}just Runs
        assertTrue(guard.applyResolved(resolved))
        assertTrue(prefs.getStringSet("retired_grant_addon_ids",emptySet()).orEmpty().isEmpty())
        assertEquals(replacement.id,prefs.getString("granted_addon_id:cloud_stream",null))
    }
    @Test fun cancelledRefreshPreservesOldIdentityAndPropagatesCancellation()=runBlocking {
        coEvery{streams.ensureCustomAddons(any())}throws CancellationException("Cancelled")
        try{guard().applyResolved(resolved);fail("Cancellation must propagate")}catch(_:CancellationException){}
        assertEquals(addon.id,prefs.getString("granted_addon_id:cloud_stream",null))
        coVerify(exactly=0){streams.removeAddon(any())}
    }
    @Test fun startupWaitsPastEmptyActivationStateInsteadOfSilentlySkipping()=runBlocking {
        every{iptv.observeActivationConfig()}returns flow { emit(IptvConfig());delay(10);emit(config) }
        assertTrue(guard().enforce("startup",0))
        coVerify(exactly=1){iptv.verifyXtreamLogin("https://tv.extremeiptv.net","primary","secret")}
    }
    @Test fun inconclusivePackageReadKeepsTheGrantAndRetriesLater()=runBlocking {
        every{entitlements.resolve(any())}returns XtreamEntitlementsResult.Unresolved("unavailable")
        assertFalse(guard().enforce("fixture",0))
        assertEquals(addon.id,prefs.getString("granted_addon_id:cloud_stream",null))
        assertEquals(0L,prefs.getLong("last_check_at",0L))
        coVerify(exactly=0){streams.removeAddon(any())}
    }
}
