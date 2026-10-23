package com.arflix.tv.management

import android.content.Context
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManagementActionsTest {
    private val repository = mockk<IptvRepository>(relaxed = true)
    private val profiles = mockk<ProfileManager>()
    private val context = mockk<Context>()
    private val account = ManagementAccount("fixture", "private-password")
    private val config = IptvConfig(m3uUrl = "https://tv.extremeiptv.net/get.php?username=fixture&password=private-password")
    private fun actions(): ManagementActions {
        coEvery { profiles.getProfileId() } returns "profile-one"
        every { repository.observeConfig() } returns flowOf(config)
        return ManagementActions(context, repository, profiles)
    }
    @Test fun settingsSnapshotIsReadOnlyAndContainsNoCredentialsOrFavouriteValues() = runTest {
        val actions = actions()
        every { repository.observeFavoriteChannels() } returns flowOf(listOf("secret-channel-id"))
        every { repository.observeFavoriteGroups() } returns flowOf(listOf("Private group"))
        val receipt = actions.execute("command", "get_settings", account.scope)
        assertEquals("succeeded", receipt.status)
        assertEquals("1", receipt.snapshot!!["playlist_count"])
        assertEquals(managementHash("profile-one"), receipt.snapshot["profile_ref"])
        val text = receipt.snapshot.toString()
        listOf("private-password", "tv.extremeiptv.net", "secret-channel-id", "Private group").forEach { assertFalse(text.contains(it)) }
        coVerify(exactly = 0) { repository.savePlaylists(any()) }
    }
    @Test fun playlistRefreshNeverPurgesGuidesOrChangesLoginConfiguration() = runTest {
        val actions = actions()
        coEvery { repository.loadSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns IptvSnapshot()
        assertEquals("succeeded", actions.execute("command", "refresh_playlist", account.scope).status)
        coVerify(exactly = 1) { repository.loadSnapshot(true, false, false, any(), true, any(), any(), any()) }
        coVerify(exactly = 0) { repository.purgeAllIptvSourceCaches() }
        coVerify(exactly = 0) { repository.savePlaylists(any()) }
    }
    @Test fun guideActionRequestsTheSharedJobWithoutPretendingImportFinished() = runTest {
        val actions = actions(); val job = Job()
        every { repository.requestGuideRefresh(force = true) } returns job
        val receipt = actions.execute("command", "reload_epg", account.scope)
        assertEquals("succeeded", receipt.status)
        assertTrue(receipt.summary.contains("NOT confirmation"))
        verify(exactly = 1) { repository.requestGuideRefresh(force = true) }
        assertTrue(job.isActive); job.cancel()
    }
    @Test fun profileSwitchDuringReadDiscardsTheSnapshot() = runTest {
        val actions = actions()
        every { repository.observeFavoriteChannels() } returns flowOf(emptyList())
        every { repository.observeFavoriteGroups() } returns flowOf(emptyList())
        coEvery { profiles.getProfileId() } returnsMany listOf("profile-one", "profile-one", "other-profile")
        val receipt = actions.execute("command", "get_settings", account.scope)
        assertEquals("failed", receipt.status); assertNull(receipt.snapshot)
    }
    @Test fun wrongAccountCannotExecuteRemoteActionsAndErrorsNeverEchoSecrets() = runTest {
        val actions = actions()
        assertEquals("failed", actions.execute("command", "refresh_playlist", "wrong-account").status)
        coVerify(exactly = 0) { repository.loadSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) }
        coEvery { repository.loadSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("private-password https://provider.test/token")
        val receipt = actions.execute("command", "refresh_playlist", account.scope)
        assertEquals("failed", receipt.status); assertFalse(receipt.summary.contains("private-password")); assertFalse(receipt.summary.contains("https://"))
    }
    @Test fun remoteSortingUsesCompareAndSetAndReturnsTheUpdatedRevision() = runTest {
        val actions=actions();val current=MutableStateFlow(config)
        every { repository.observeConfig() } returns current
        every { repository.observeFavoriteChannels() } returns flowOf(emptyList())
        every { repository.observeFavoriteGroups() } returns flowOf(emptyList())
        coEvery { repository.compareAndSetManagedSortOrder("profile-one","provider","name",any()) } coAnswers {
            assertTrue(arg<(com.arflix.tv.data.repository.IptvConfig)->Boolean>(3)(config))
            current.value=config.copy(sortOrder="name");true
        }
        val profile=managementHash("profile-one")
        val result=actions.execute("command","set_settings",account.scope,mapOf("profile_ref" to profile,"expected_revision" to managementSettingsRevision(profile,account.scope,"provider"),"sort_order" to "name"))
        assertEquals("succeeded",result.status)
        assertEquals("name",result.snapshot!!["sort_order"])
        assertEquals(managementSettingsRevision(profile,account.scope,"name"),result.snapshot["settings_revision"])
        coVerify(exactly=0) { repository.savePlaylists(any()) }
    }
    @Test fun staleWrongProfileAndUnsupportedSettingsNeverCallTheWriteMethod() = runTest {
        val actions=actions();val profile=managementHash("profile-one")
        val valid=mapOf("profile_ref" to profile,"expected_revision" to managementSettingsRevision(profile,account.scope,"provider"),"sort_order" to "name")
        for(payload in listOf(valid+mapOf("profile_ref" to managementHash("other")),valid+mapOf("expected_revision" to "0".repeat(64)),valid+mapOf("update_url" to "https://forbidden.example"))) {
            assertEquals("failed",actions.execute("command","set_settings",account.scope,payload).status)
        }
        coVerify(exactly=0) { repository.compareAndSetManagedSortOrder(any(),any(),any(),any()) }
    }
    @Test fun compareAndSetConflictReturnsFailureInsteadOfClaimingAnAppliedSetting() = runTest {
        val actions=actions();val profile=managementHash("profile-one")
        coEvery { repository.compareAndSetManagedSortOrder(any(),any(),any(),any()) } returns false
        val result=actions.execute("command","set_settings",account.scope,mapOf("profile_ref" to profile,"expected_revision" to managementSettingsRevision(profile,account.scope,"provider"),"sort_order" to "name"))
        assertEquals("failed",result.status);assertNull(result.snapshot)
    }
    @Test fun remoteLiveBufferIsRevisionGuardedAndOnlyWritesDeviceBufferPreference() = runTest {
        val actions = actions()
        var seconds = 0
        coEvery { repository.livePrebufferSeconds() } answers { seconds }
        every { repository.observeFavoriteChannels() } returns flowOf(emptyList())
        every { repository.observeFavoriteGroups() } returns flowOf(emptyList())
        coEvery { repository.compareAndSetManagedLivePrebuffer("profile-one", 0, 5, any()) } coAnswers {
            assertTrue(arg<(IptvConfig) -> Boolean>(3)(config)); seconds = 5; true
        }
        val profile = managementHash("profile-one")
        val payload = mapOf("profile_ref" to profile, "expected_revision" to managementLiveBufferRevision(profile, account.scope, 0), "prebuffer_seconds" to "5")
        val result = actions.execute("command", "set_live_buffer", account.scope, payload)
        assertEquals("succeeded", result.status)
        assertEquals("5", result.snapshot!!["live_prebuffer_seconds"])
        assertTrue(result.summary.contains("next standard live channel tune"))
        assertEquals(managementLiveBufferRevision(profile, account.scope, 5), result.snapshot["live_buffer_revision"])
        coVerify(exactly = 0) { repository.savePlaylists(any()) }
        coVerify(exactly = 0) { repository.compareAndSetManagedSortOrder(any(), any(), any(), any()) }
    }
    @Test fun staleWrongProfileAndUnboundedLiveBufferSettingsNeverWrite() = runTest {
        val actions = actions()
        coEvery { repository.livePrebufferSeconds() } returns 0
        val profile = managementHash("profile-one")
        val valid = mapOf("profile_ref" to profile, "expected_revision" to managementLiveBufferRevision(profile, account.scope, 0), "prebuffer_seconds" to "2")
        for (payload in listOf(valid + ("prebuffer_seconds" to "999"), valid + ("expected_revision" to "0".repeat(64)), valid + ("profile_ref" to managementHash("other")), valid + ("url" to "https://forbidden.test"))) {
            assertEquals("failed", actions.execute("command", "set_live_buffer", account.scope, payload).status)
        }
        coVerify(exactly = 0) { repository.compareAndSetManagedLivePrebuffer(any(), any(), any(), any()) }
        coEvery { repository.compareAndSetManagedLivePrebuffer(any(), any(), any(), any()) } returns false
        val conflict = actions.execute("command", "set_live_buffer", account.scope, valid)
        assertEquals("failed", conflict.status); assertNull(conflict.snapshot)
    }
}
