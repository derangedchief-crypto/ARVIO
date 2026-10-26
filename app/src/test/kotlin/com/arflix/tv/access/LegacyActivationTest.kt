package com.arflix.tv.access

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.repository.*
import com.arflix.tv.management.ExtraPlaylistLicence
import com.arflix.tv.management.ExtraPlaylistDecision
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class LegacyActivationTest {
    @After fun cleanup(){unmockkAll()}
    @Test(timeout=60000) fun oneCodeConfirmationPinsPrimaryWithoutDroppingLegacyExtrasOrReopeningTheGateMidSubmit()=runBlocking {
        mockkConstructor(ActivationVault::class,PrimaryLineVerifier::class,ExtraPlaylistLicence::class)
        every{anyConstructed<ActivationVault>().load()}returns null
        every{anyConstructed<ActivationVault>().save(any())}just Runs
        coEvery{anyConstructed<PrimaryLineVerifier>().check(any())}returns LineDecision.Valid(900)
        coEvery{anyConstructed<ExtraPlaylistLicence>().decision(any())}returns ExtraPlaylistDecision(false)
        val context=ApplicationProvider.getApplicationContext<Application>()
        val profile=mockk<ProfileManager>(relaxed=true)
        every{profile.getProfileIdSync()}returns "legacy-guard"
        every{profile.activeProfileId}returns flowOf("legacy-guard")
        every{profile.profileStringKey(any())}answers{stringPreferencesKey("profile_legacy-guard_${firstArg<String>()}")}
        every{profile.profileStringKeyFor(any(),any())}answers{stringPreferencesKey("profile_${firstArg<String>()}_${secondArg<String>()}")}
        val saved=(1..3).map{IptvPlaylistEntry("list_$it","Existing $it","https://other$it.test/list.m3u")}
        context.settingsDataStore.edit{p->p[stringPreferencesKey("profile_legacy-guard_iptv_playlists_json")]=Gson().toJson(saved)}
        val access=PrimaryAccessRepository(context);access.refresh();assertFalse(access.state.value.bound)
        val repo=IptvRepository(context,OkHttpClient(),profile,mockk(relaxed=true),IptvEpgPlaybackGate(),mockk(relaxed=true),access)
        val credentials=IptvActivationCredentials("primary","secret")
        repo.saveVerifiedActivation(credentials,IptvPlaylistEntry("list_1","Primary","https://tv.extremeiptv.net primary secret"))
        assertFalse(access.state.value.primaryAllowed) // recovery UI must survive the submission
        val all=repo.observeConfig().first();assertEquals(4,all.playlists.size)
        saved.forEach{old->assertTrue(all.playlists.any{it.id==old.id&&it.m3uUrl==old.m3uUrl})}
        access.finishActivation();assertTrue(access.state.value.primaryAllowed);assertFalse(access.extrasAllowed())
        assertEquals(1,access.playbackConfig(all).playlists.size)
        try{repo.savePlaylists(saved);fail("A working extra cannot replace the bound activation")}catch(_:com.arflix.tv.management.ExtraPlaylistLicenceException){}
        assertEquals(all,repo.observeConfig().first())
        repo.savePlaylists(all.playlists.map{it.copy(name="Retained")});assertEquals(4,repo.observeConfig().first().playlists.size)
    }
}
