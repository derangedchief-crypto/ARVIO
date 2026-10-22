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
}
