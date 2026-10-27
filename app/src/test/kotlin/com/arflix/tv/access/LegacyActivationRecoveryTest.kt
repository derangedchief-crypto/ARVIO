package com.arflix.tv.access

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.management.ManagementAccount
import com.arflix.tv.management.ManagementSession
import com.arflix.tv.management.ManagementVault
import com.arflix.tv.util.settingsDataStore
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class LegacyActivationRecoveryTest {
    private val primary=ActivationAnchor("primary","secret")
    private val extra=ActivationAnchor("extra","other")
    private fun session(a:ActivationAnchor)=ManagementSession(
        scope=ManagementAccount(a.username,a.password).scope,installationId="a".repeat(32),
        keyFingerprint="b".repeat(64),deviceId="device",credential="c".repeat(64),policy="active")

    @After fun cleanup(){unmockkAll()}
    @Test fun onlyGenuinelyFreshStateMayShowFirstActivation(){
        assertEquals(LegacyRecovery.Fresh,identifyLegacyAccount(false,emptyList(),emptyList()))
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,emptyList(),emptyList()))
    }
    @Test fun originalApprovedIdentityIsIndependentOfOrderLabelAndProfile(){
        for(candidates in listOf(listOf(extra,primary),listOf(primary,extra),listOf(primary,extra,primary)))
            assertEquals(LegacyRecovery.Identified(primary),identifyLegacyAccount(true,candidates,listOf(session(primary))))
    }
    @Test fun soleSavedProviderAccountWithoutIdentityEvidenceIsNotAdopted(){
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(extra),emptyList()))
    }
    @Test fun pendingEnrollmentIsNotAnApprovedIdentity(){
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(extra),listOf(session(extra).copy(credential=null,policy=null))))
    }
    @Test fun removingPrimaryCannotPromoteAnApprovedExtra(){
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(extra),listOf(session(primary),session(extra))))
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(extra),listOf(session(primary))))
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(extra),listOf(session(primary).copy(credential=null,policy=null),session(extra))))
    }
    @Test fun multipleHistoricalApprovalsAndMultiplePasswordsAreAmbiguous(){
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(primary,extra),listOf(session(primary),session(extra))))
        assertEquals(LegacyRecovery.Unresolved,identifyLegacyAccount(true,listOf(primary,primary.copy(password="changed")),listOf(session(primary))))
    }
    @Test fun inactivePrimaryIdentityIsStillPrimaryNeverAnActiveFallback(){
        assertEquals(LegacyRecovery.Identified(primary),identifyLegacyAccount(true,listOf(extra,primary),listOf(session(primary).copy(policy="suspended"))))
    }
    @Test fun onlyOfficialHttpsProviderCredentialsAreConsidered(){
        assertEquals(primary,legacyProviderAnchor("https://tv.extremeiptv.net primary secret"))
        assertEquals(primary,legacyProviderAnchor("https://tv.extremeiptv.net/get.php?username=primary&password=secret"))
        for(raw in listOf("http://tv.extremeiptv.net primary secret","https://foreign.test primary secret","https://tv.extremeiptv.net:444 primary secret","https://user@tv.extremeiptv.net primary secret"))
            assertNull(legacyProviderAnchor(raw))
    }
    @Test fun reads117FormatAcrossProfilesWithoutWritingAnySettingsOrSourceIds()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val folder=File(context.noBackupFilesDir,"device-management-v1").apply{mkdirs()}
        val approved=session(primary)
        File(folder,"${approved.scope}.enc").writeText("encrypted-session-fixture")
        mockkConstructor(ManagementVault::class)
        every{anyConstructed<ManagementVault>().load(approved.scope)}returns approved
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
            prefs[stringPreferencesKey("profile_original_iptv_playlists_json")]=
                """[{"id":"list_1","name":"Existing name","m3uUrl":"https://tv.extremeiptv.net primary secret","enabled":true}]"""
            prefs[stringPreferencesKey("profile_other_iptv_playlists_json")]=
                """[{"id":"list_1","name":"Primary renamed extra","m3uUrl":"https://tv.extremeiptv.net extra other","enabled":true}]"""
            prefs[stringPreferencesKey("profile_original_iptv_favorite_channels") ]="saved-channel-ids"
            prefs[stringPreferencesKey("profile_original_iptv_hidden_groups") ]="saved-hidden-groups"
            prefs[stringPreferencesKey("unrelated_setting") ]="unchanged"
        }
        val before=context.settingsDataStore.data.first().asMap()
        assertEquals(LegacyRecovery.Identified(primary),LegacyActivationRecovery(context).recover())
        assertEquals(before,context.settingsDataStore.data.first().asMap())
        verify(exactly=0){anyConstructed<ManagementVault>().save(any())}
    }
    @Test fun unmanaged117DataIsPreservedButCannotBeClaimedAsRecoveredActivation()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
            prefs[stringPreferencesKey("profile_existing_iptv_playlists_json")]=
                """[{"id":"list_1","name":"Primary","m3uUrl":"https://tv.extremeiptv.net extra other","enabled":true}]"""
            prefs[stringPreferencesKey("profile_existing_iptv_favorite_channels") ]="old-favourites"
            prefs[stringPreferencesKey("profile_existing_iptv_group_order") ]="old-order"
            prefs[booleanPreferencesKey("xtream_jellyfin_gate_completed")]=true
        }
        val before=context.settingsDataStore.data.first().asMap()
        assertEquals(LegacyRecovery.Unresolved,LegacyActivationRecovery(context).recover())
        assertEquals(before,context.settingsDataStore.data.first().asMap())
    }
}
