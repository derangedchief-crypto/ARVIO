package com.arflix.tv.ui.screens.xtreamgate

import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.IptvActivationRepository
import com.arflix.tv.data.repository.IptvActivationCredentials
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.XtreamEntitlementsRepository
import com.arflix.tv.data.repository.XtreamEntitlementsResult
import com.arflix.tv.data.repository.XtreamLoginCheckResult
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.coVerifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class XtreamGateSubmitTest {
    @Test fun freshProfileActivationCompletesFromOneSubmitAndSavesToThatProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activation = mockk<IptvActivationRepository>()
        val iptv = mockk<IptvRepository>(relaxed = true)
        val profiles = mockk<ProfileRepository>(relaxed = true)
        val manager = mockk<ProfileManager>(relaxed = true)
        val entitlements = mockk<XtreamEntitlementsRepository>()
        coEvery { activation.resolve("123456") } returns Result.success(IptvActivationCredentials("fixture-user", "fixture-pass"))
        coEvery { iptv.verifyXtreamLogin(any(), any(), any()) } returns XtreamLoginCheckResult(true)
        coEvery { profiles.createDefaultProfileIfNeeded() } returns Profile(id = "fresh-profile", name = "Default")
        coEvery { iptv.loadSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns IptvSnapshot(
            channels = listOf(IptvChannel("list_1:1", "Channel", "https://provider.test/live/1.ts", "News")))
        every { entitlements.resolve(any()) } returns XtreamEntitlementsResult.Unresolved("fixture")
        val vm = XtreamGateViewModel(iptv, activation, profiles, manager, entitlements, mockk(relaxed = true))
        try {
            var success = 0
            vm.onActivationCodeChange("123456")
            vm.submit { success++ }
            runCurrent()
            assertEquals(1, success)
            assertFalse(vm.uiState.value.isSubmitting)
            assertNull(vm.uiState.value.errorMessage)
            coVerify(exactly = 1) { activation.resolve("123456") }
            coVerify(exactly = 1) { iptv.saveVerifiedActivation(any(),any()) }
            coVerifyOrder {
                profiles.setActiveProfile("fresh-profile")
                manager.setCurrentProfileId("fresh-profile")
                iptv.saveVerifiedActivation(any(),any())
            }
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun firstActionStartsResolutionAndDuplicateImeOrClickCannotSubmitTwice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activation = mockk<IptvActivationRepository>()
        coEvery { activation.resolve(any()) } coAnswers { awaitCancellation() }
        val vm = XtreamGateViewModel(mockk(relaxed = true), activation, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        try {
            vm.onActivationCodeChange("123456")
            vm.submit {}
            assertTrue(vm.uiState.value.isSubmitting)
            assertEquals("Signing in…", vm.uiState.value.progressText)
            vm.submit {}
            runCurrent()
            coVerify(exactly = 1) { activation.resolve("123456") }
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun invalidCodeShowsFeedbackWithoutStartingARequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activation = mockk<IptvActivationRepository>(relaxed = true)
        val vm = XtreamGateViewModel(mockk(relaxed = true), activation, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        try {
            vm.onActivationCodeChange("12a345")
            vm.submit {}; runCurrent()
            assertFalse(vm.uiState.value.isSubmitting)
            assertEquals("Enter your six-digit activation code.", vm.uiState.value.errorMessage)
            coVerify(exactly = 0) { activation.resolve(any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
