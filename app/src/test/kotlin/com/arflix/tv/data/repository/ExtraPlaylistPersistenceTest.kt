package com.arflix.tv.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.management.ExtraPlaylistLicence
import com.arflix.tv.management.ExtraPlaylistLicenceException
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class ExtraPlaylistPersistenceTest {
    @Test(timeout=60000) fun persistenceImportsAndManagedRestoresRecheckWithoutChangingSavedViewing()=runBlocking {
        val profile=mockk<ProfileManager>(relaxed=true);every{profile.getProfileIdSync()}returns "addon-test"
        every{profile.activeProfileId}returns kotlinx.coroutines.flow.flowOf("addon-test")
        every{profile.profileStringKey(any())}answers{androidx.datastore.preferences.core.stringPreferencesKey("profile_addon-test_${firstArg<String>()}")}
        every{profile.profileStringKeyFor(any(),any())}answers{androidx.datastore.preferences.core.stringPreferencesKey("profile_${firstArg<String>()}_${secondArg<String>()}")}
        val licence=mockk<ExtraPlaylistLicence>();coEvery{licence.authorize(any())}throws ExtraPlaylistLicenceException("Locked")
        val repo=IptvRepository(ApplicationProvider.getApplicationContext(),OkHttpClient(),profile,mockk(relaxed=true),IptvEpgPlaybackGate(),licence,primaryAccessFixture())
        repo.clearConfig();repo.saveConfig("https://a.test/list.m3u","");val before=repo.observeConfig().first();coVerify(exactly=0){licence.authorize(any())}
        val extra=IptvPlaylistEntry("list_2","Extra","https://b.test/list.m3u");val target=before.copy(playlists=before.playlists+extra)
        suspend fun denied(block:suspend()->Unit){try{block();fail("Expected licence denial")}catch(_:ExtraPlaylistLicenceException){};assertEquals(before,repo.observeConfig().first())}
        denied{repo.savePlaylists(target.playlists)}
        denied{repo.saveStalkerPortals(listOf(StalkerPortalEntry("portal","Portal","https://p.test","00:1A:79:00:00:01")))}
        denied{repo.saveStalkerConfig("https://p.test","00:1A:79:00:00:01")}
        denied{repo.importCloudConfigForProfile("addon-test",IptvCloudProfileState(m3uUrl=before.m3uUrl,playlists=target.playlists))}
        denied{repo.compareAndSetManagedSources("addon-test",before.copy(sortOrder="provider"),target,accountMatches={true})}
        coEvery{licence.authorize(any())}just Runs;repo.savePlaylists(target.playlists);assertEquals(2,repo.observeConfig().first().playlists.size)
        coEvery{licence.authorize(any())}throws ExtraPlaylistLicenceException("Revoked")
        repo.savePlaylists(target.playlists.map{it.copy(name="Retained",enabled=true)});assertEquals(2,repo.observeConfig().first().playlists.size)
        repo.savePlaylists(listOf(target.playlists.first()));assertEquals(1,repo.observeConfig().first().playlists.size)
    }
}
