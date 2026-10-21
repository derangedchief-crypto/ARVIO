package com.arflix.tv.updater

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RequiredUpdateControllerTest {
    private val controllers = mutableListOf<RequiredUpdateController>()
    private val release = AppUpdate("2.0.103", "Extreme TV 2.0.103", "Fixes", null,
        "ExtremeTV.apk", "https://github.com/example/releases/ExtremeTV.apk", 100)
    private var installed = "2.0.102"
    private val repository = mockk<AppUpdateRepository>()
    private val statuses = UpdateStatusManager()

    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("required_update_v1", 0).edit().clear().commit()
        every { repository.supportsSelfUpdate() } returns true
        every { repository.getInstalledVersionName() } answers { installed }
        coEvery { repository.getLatestUpdate() } returns Result.success(release)
    }

    private fun controller() = RequiredUpdateController(RuntimeEnvironment.getApplication(), repository,
        mockk(relaxed = true), statuses).also { controllers.add(it) }

    @After fun cleanup() {
        controllers.forEach {
            val field = RequiredUpdateController::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(it) as CoroutineScope).cancel()
        }
    }

    @Test fun launchChecksBeforeAllowingNavigationAndRequiresNewRelease() = runBlocking {
        val controller = controller()
        assertTrue(controller.state.value.checking)
        controller.refresh()
        assertFalse(controller.state.value.checking)
        assertEquals(release, controller.state.value.update)
        assertTrue(controller.state.value.blocksApp)
    }

    @Test fun currentAndOlderReleasesDoNotBlock() = runBlocking {
        installed = "2.0.104"
        val controller = controller()
        controller.refresh()
        assertFalse(controller.state.value.blocksApp)
    }

    @Test fun initialConnectionFailureDoesNotInventRequiredUpdate() = runBlocking {
        coEvery { repository.getLatestUpdate() } returns Result.failure(java.io.IOException("Offline"))
        val controller = controller()
        controller.refresh()
        assertFalse(controller.state.value.blocksApp)
    }

    @Test fun knownRequirementSurvivesRestartAndConnectionFailure() = runBlocking {
        controller().refresh()
        coEvery { repository.getLatestUpdate() } returns Result.failure(java.io.IOException("Offline"))
        val restarted = controller()
        assertEquals(release, restarted.state.value.update)
        restarted.refresh()
        assertTrue(restarted.state.value.blocksApp)
    }

    @Test fun ignoredTagsAndInstallerCancellationCannotUnlockApp() = runBlocking {
        val controller = controller()
        controller.refresh()
        statuses.sessionIgnoredTag = release.tag
        statuses.updateStatus(UpdateStatus.Failure("Installation cancelled", release))
        statuses.reset()
        assertTrue(controller.state.value.blocksApp)
        assertEquals(release, controller.state.value.update)
    }

    @Test fun installerSuccessAloneDoesNotUnlockOldInstalledPackage() = runBlocking {
        val controller = controller()
        controller.refresh()
        statuses.updateStatus(UpdateStatus.Success(release))
        delay(50)
        assertTrue(controller.state.value.blocksApp)
    }

    @Test fun actualPackageReplacementSatisfiesPersistedRequirement() = runBlocking {
        val controller = controller()
        controller.refresh()
        installed = "2.0.103"
        statuses.updateStatus(UpdateStatus.Success(release))
        withTimeout(3000) { while (controller.state.value.blocksApp) delay(10) }
        assertNull(controller().state.value.update)
    }

    @Test fun releasePublishedDuringSessionBlocksAtNextPoll() = runBlocking {
        coEvery { repository.getLatestUpdate() } returns Result.success(release.copy(tag = installed))
        val controller = controller()
        controller.refresh()
        assertFalse(controller.state.value.blocksApp)
        coEvery { repository.getLatestUpdate() } returns Result.success(release)
        ShadowSystemClock.advanceBy(Duration.ofMillis(RequiredUpdateController.POLL_INTERVAL_MS))
        controller.refresh()
        assertTrue(controller.state.value.blocksApp)
    }

    @Test fun temporarilyOlderRemoteReleaseCannotClearKnownRequirement() = runBlocking {
        val controller = controller()
        controller.refresh()
        coEvery { repository.getLatestUpdate() } returns Result.success(release.copy(tag = installed))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        controller.refresh()
        assertEquals(release, controller.state.value.update)
    }

    @Test fun quickResumesDoNotRepeatTheNetworkCheck() = runBlocking {
        val controller = controller()
        controller.refresh()
        controller.refresh()
        coVerify(exactly = 1) { repository.getLatestUpdate() }
    }

    @Test fun interruptedLaunchCheckRetriesImmediatelyOnResume() = runBlocking {
        coEvery { repository.getLatestUpdate() } throws kotlinx.coroutines.CancellationException("Activity stopped")
        val controller = controller()
        assertTrue(runCatching { controller.refresh() }.isFailure)
        coEvery { repository.getLatestUpdate() } returns Result.success(release)
        controller.refresh()
        assertEquals(release, controller.state.value.update)
        assertFalse(controller.state.value.checking)
    }

    @Test fun manualDiscoveryAlsoActivatesGlobalGate() = runBlocking {
        coEvery { repository.getLatestUpdate() } returns Result.success(release.copy(tag = installed))
        val controller = controller()
        controller.refresh()
        statuses.updateStatus(UpdateStatus.UpdateAvailable(release))
        withTimeout(3000) { while (controller.state.value.update == null) delay(10) }
        assertTrue(controller.state.value.blocksApp)
    }

    @Test fun unsupportedInstallDoesNotGetTrappedInSideloadGate() = runBlocking {
        every { repository.supportsSelfUpdate() } returns false
        val controller = controller()
        controller.refresh()
        assertFalse(controller.state.value.blocksApp)
    }

    @Test fun malformedVersionCannotForceAnUninstallableUpdate() = runBlocking {
        coEvery { repository.getLatestUpdate() } returns Result.success(release.copy(tag = "staging-testing"))
        val controller = controller()
        controller.refresh()
        assertFalse(controller.state.value.blocksApp)
    }
}
