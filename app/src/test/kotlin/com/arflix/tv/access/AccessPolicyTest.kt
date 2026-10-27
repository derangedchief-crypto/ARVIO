package com.arflix.tv.access

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.data.repository.*
import com.arflix.tv.management.ExtraPlaylistDecision
import com.arflix.tv.management.ExtraPlaylistLicence
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class AccessPolicyTest {
    private val anchor=ActivationAnchor("primary","secret")
    private fun response(auth:Int=1,status:String="Active",expiry:Any=1060)=JSONObject().put("user_info",JSONObject().put("auth",auth).put("status",status).put("exp_date",expiry)).put("server_info",JSONObject().put("timestamp_now",1000))
    @Before fun setup(){
        mockkConstructor(ActivationVault::class,PrimaryLineVerifier::class,ExtraPlaylistLicence::class)
        every{anyConstructed<ActivationVault>().load()}returns anchor
        every{anyConstructed<ActivationVault>().save(any())}just Runs
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Valid(900)
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}returns ExtraPlaylistDecision(false)
    }
    @After fun cleanup(){unmockkAll()}
    private fun repo()=PrimaryAccessRepository(ApplicationProvider.getApplicationContext())
    @Test fun primaryVerificationGateContainsNoManualRetryOrSyncControl(){
        val source=java.io.File("src/main/kotlin/com/arflix/tv/ui/components/PrimaryActivationGate.kt").readText()
        assertFalse(source.contains("TextButton("));assertFalse(source.contains("onClick"));assertFalse(source.contains("Retry primary verification"))
    }
    @Test fun allPlaybackRequiresALivePrimaryLeaseEvenForCloudLinks()=runBlocking {
        val r=repo();r.refresh();val binding=r.requirePlayback();assertEquals(binding,r.requirePlayback(binding))
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Invalid;r.refresh()
        try{r.requirePlayback(binding);fail("Cloud and cached URLs must not bypass the primary")}catch(_:PrimaryPlaybackDeniedException){}
    }
    @Test fun authActiveButExpiredIsInvalid(){assertEquals(LineDecision.Invalid,parsePrimaryDecision(response(expiry=999),null,0))}
    @Test fun disabledBannedAndInvalidCredentialsAreImmediateNegatives(){for(status in listOf("Expired","Banned","Disabled"))assertEquals(LineDecision.Invalid,parsePrimaryDecision(response(status=status),null,0));assertEquals(LineDecision.Invalid,parsePrimaryDecision(response(auth=0),null,0))}
    @Test fun malformedOrMissingExpiryIsNotAValidLine(){val j=response();j.getJSONObject("user_info").remove("exp_date");assertEquals(LineDecision.Unknown,parsePrimaryDecision(j,null,0));assertEquals(LineDecision.Unknown,parsePrimaryDecision(response(expiry="garbage"),null,0))}
    @Test fun finiteExpiryUsesServerTimeAndConservativeNetworkBudget(){assertEquals(LineDecision.Valid(58),parsePrimaryDecision(response(),null,1500));assertEquals(LineDecision.Invalid,parsePrimaryDecision(response(expiry=1001),null,2000))}
    @Test fun freshValidationIsStillCappedAtFifteenMinutes(){assertEquals(LineDecision.Valid(900),parsePrimaryDecision(response(expiry=999999),null,0));assertEquals(LineDecision.Valid(900),parsePrimaryDecision(response(expiry=JSONObject.NULL),null,0))}
    @Test fun workerExpiryAndCredentialNegativesCannotMintGrace(){
        assertEquals(LineDecision.Invalid,parseWorkerPrimary(JSONObject().put("schema_version",1).put("active",false).put("valid_for_seconds",900),0))
        assertEquals(LineDecision.Valid(8),parseWorkerPrimary(JSONObject().put("schema_version",1).put("active",true).put("valid_for_seconds",10),2000))
        assertEquals(LineDecision.Unknown,parseWorkerPrimary(JSONObject().put("schema_version",1).put("active",true).put("valid_for_seconds",901),0))
    }
    @Test fun clockRollbackWrongIdentityAndExpiredLeaseCannotUnlock(){val l=AccessLease.grant("a",100,900);assertFalse(l.valid("a",99));assertFalse(l.valid("b",200));assertTrue(l.valid("a",899999));assertFalse(l.valid("a",900100));assertFalse(AccessLease("a",0,900001).valid("a",1))}
    @Test fun primaryIdentityIsNotTheFirstPlaylistOrAnyOtherValidAccount(){assertTrue(anchor.matches("https://tv.extremeiptv.net primary secret"));assertFalse(anchor.matches("https://tv.extremeiptv.net extra secret"));assertFalse(anchor.matches("https://foreign.test primary secret"));assertTrue(anchor.matchesStream("https://tv.extremeiptv.net/live/primary/secret/1.ts"));assertFalse(anchor.matchesStream("https://tv.extremeiptv.net/live/extra/secret/1.ts"))}
    @Test fun editableListOneAndSortOrderCannotExemptAnExtraFromLicensing(){
        val a=IptvPlaylistEntry("activation","Real primary","https://tv.extremeiptv.net primary secret")
        val b=IptvPlaylistEntry("list_1","Promoted extra","https://other.test/old")
        val before=IptvConfig(playlists=listOf(b,a));val target=before.copy(playlists=listOf(b.copy(m3uUrl="https://other.test/new"),a))
        assertTrue(requiresExtraGrant(anchor,before,target));assertFalse(requiresExtraGrant(anchor,before,before.copy(playlists=listOf(a,b.copy(name="Rename")))))
    }
    @Test fun allLegacyExtrasAreFilteredWithoutChangingTheirSavedData()=runBlocking {
        val r=repo();r.refresh();val extra=IptvPlaylistEntry("list_1","Old extra","https://foreign.test/old.m3u");val saved=IptvConfig(m3uUrl=extra.m3uUrl,playlists=listOf(extra),stalkerPortals=listOf(StalkerPortalEntry("s","Portal","https://portal.test","00:1A:79:00:00:01")))
        val playable=r.playbackConfig(saved);assertEquals(1,playable.playlists.size);assertTrue(anchor.matches(playable.m3uUrl));assertTrue(playable.stalkerPortals.isEmpty());assertEquals(listOf(extra),saved.playlists);assertEquals(1,saved.stalkerPortals.size)
    }
    @Test fun logicalBindingRetainsDistinctSavedViewsEvenWhenCredentialsAreDuplicated()=runBlocking {
        val r=repo();r.refresh();val p=IptvPlaylistEntry("list_1","Primary","https://tv.extremeiptv.net primary secret")
        val raw=IptvConfig(playlists=listOf(p,p.copy(id="list_2",name="Separate guide view")))
        assertEquals(listOf("list_1","list_2"),r.editingConfig(raw).playlists.map{it.id});assertEquals(1,r.playbackConfig(raw).playlists.size)
    }
    @Test fun confirmedInvalidCannotUsePriorGraceEvenIfTheNextRequestFails()=runBlocking {
        val r=repo();r.refresh();assertTrue(r.primaryAllowed());coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Invalid;r.refresh();assertFalse(r.primaryAllowed())
        assertEquals(PrimaryGatePage.ACCOUNT_INACTIVE,r.state.value.gatePage())
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Unknown;r.refresh();assertFalse(r.primaryAllowed());assertFalse(r.extrasAllowed())
        assertEquals(PrimaryGatePage.ACCOUNT_INACTIVE,r.state.value.gatePage())
    }
    @Test fun restoredAccountReturnsAutomaticallyWithoutAnotherCodeOrBindingWrite()=runBlocking {
        val r=repo();r.refresh();coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Invalid;r.refresh()
        assertEquals(PrimaryGatePage.ACCOUNT_INACTIVE,r.state.value.gatePage())
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Valid(900);r.refresh()
        assertEquals(PrimaryGatePage.CONTENT,r.state.value.gatePage());assertTrue(r.state.value.bound)
        verify(exactly=0){anyConstructed<ActivationVault>().save(any())}
    }
    @Test fun unavailableValidationForASavedBindingDoesNotAskForActivationAgain()=runBlocking {
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Unknown
        val r=repo();r.refresh();assertEquals(PrimaryGatePage.VERIFICATION_UNAVAILABLE,r.state.value.gatePage());assertFalse(r.state.value.accountInactive)
    }
    @Test fun signedControlCenterPrimaryNegativeOverridesAnOtherwiseStaleProviderPositive()=runBlocking {
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}returns ExtraPlaylistDecision(false,primaryInvalid=true)
        val r=repo();r.refresh();assertFalse(r.primaryAllowed());assertFalse(r.extrasAllowed())
    }
    @Test fun repeatedOutagesAndRetryDoNotRefreshAWindowOrPassKnownExpiry()=runBlocking {
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Valid(10)
        val r=repo();r.refresh();coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Unknown
        ShadowSystemClock.advanceBy(Duration.ofSeconds(9));r.refresh();assertTrue(r.primaryAllowed());ShadowSystemClock.advanceBy(Duration.ofSeconds(2));r.refresh();assertFalse(r.primaryAllowed())
    }
    @Test fun licenceExpiryDoesNotDisableTheValidPrimary()=runBlocking {
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}returns ExtraPlaylistDecision(true,5)
        val r=repo();r.refresh();assertTrue(r.extrasAllowed());ShadowSystemClock.advanceBy(Duration.ofSeconds(6));assertFalse(r.extrasAllowed());assertTrue(r.primaryAllowed())
    }
    @Test fun rolloutOffDoesNotCreateOutageGraceForNeverLicensedExtras()=runBlocking {
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}returns ExtraPlaylistDecision(true,900,graceEligible=false)
        val r=repo();r.refresh();assertTrue(r.extrasAllowed())
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}throws java.io.IOException("Outage")
        r.refresh();assertFalse(r.extrasAllowed());assertTrue(r.primaryAllowed())
    }
    @Test fun processRestartNeverCreatesFreshGraceFromPersistedCredentials()=runBlocking {
        val old=repo();old.refresh();assertTrue(old.primaryAllowed());coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Unknown
        val restarted=repo();restarted.refresh();assertFalse(restarted.primaryAllowed())
    }
    @Test fun noTrustedLegacyBindingMeansActivationConfirmationNotPlaylistAdoption()=runBlocking {
        every{anyConstructed<ActivationVault>().load()}returns null;val r=repo();r.refresh();assertFalse(r.state.value.bound);assertFalse(r.primaryAllowed());assertEquals(IptvConfig(),r.playbackConfig(IptvConfig(m3uUrl="https://tv.extremeiptv.net extra pass")))
    }
}
