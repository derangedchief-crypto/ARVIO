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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.arflix.tv.branding.AppBrand
import com.arflix.tv.access.PrimaryAccessRepository
import com.arflix.tv.management.RenewalCheckoutRepository
import com.arflix.tv.management.RenewalCheckoutState
import kotlinx.coroutines.flow.MutableStateFlow
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
        val vm = XtreamGateViewModel(iptv, activation, profiles, manager, entitlements, mockk(relaxed = true),mockk(relaxed = true))
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
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),mockk(relaxed = true))
        try {
            vm.onActivationCodeChange("123456")
            vm.submit {}
            assertTrue(vm.uiState.value.isSubmitting)
            vm.onActivationCodeChange("654321")
            assertEquals("123456",vm.uiState.value.activationCode)
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
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),mockk(relaxed = true))
        try {
            vm.onActivationCodeChange("12a345")
            vm.submit {}; runCurrent()
            assertFalse(vm.uiState.value.isSubmitting)
            assertEquals("Enter your six-digit activation code.", vm.uiState.value.errorMessage)
            coVerify(exactly = 0) { activation.resolve(any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun expiredBrandedCodeOffersCheckoutWithoutSavingActivationOrGrantingNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));mockkObject(AppBrand)
        every { AppBrand.id } returns "b".repeat(32)
        val activation=mockk<IptvActivationRepository>()
        val iptv=mockk<IptvRepository>(relaxed=true)
        val primary=mockk<PrimaryAccessRepository>()
        val checkout=mockk<RenewalCheckoutRepository>(relaxed=true)
        every { primary.renewalCheckout } returns checkout
        every { checkout.state } returns MutableStateFlow(RenewalCheckoutState(url="https://control.fecxbrfzku.net/renew/"+"a".repeat(64)))
        coEvery { activation.resolve("123456") } returns Result.failure(IllegalStateException("Invalid or inactive activation code."))
        val vm=XtreamGateViewModel(iptv,activation,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),primary)
        try {
            var navigated=0;vm.onActivationCodeChange("123456");vm.submit { navigated++ };runCurrent()
            assertTrue(vm.uiState.value.renewalRequested);assertEquals(0,navigated)
            coVerify(exactly=1){checkout.loadCode("123456")}
            coVerify(exactly=0){iptv.saveVerifiedActivation(any(),any())}
            coVerify(exactly=0){iptv.verifyXtreamLogin(any(),any(),any())}
            vm.cancelRenewal();assertFalse(vm.uiState.value.renewalRequested)
            assertEquals("123456",vm.uiState.value.activationCode)
        } finally {vm.viewModelScope.cancel();runCurrent();unmockkObject(AppBrand);Dispatchers.resetMain()}
    }
    @Test fun completedRenewalStillRequiresSuccessfulNormalActivation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));mockkObject(AppBrand)
        every { AppBrand.id } returns "b".repeat(32)
        val activation=mockk<IptvActivationRepository>()
        val iptv=mockk<IptvRepository>(relaxed=true)
        val primary=mockk<PrimaryAccessRepository>();val checkout=mockk<RenewalCheckoutRepository>(relaxed=true)
        val profiles=mockk<ProfileRepository>(relaxed=true)
        coEvery { profiles.createDefaultProfileIfNeeded() } returns Profile(id="renewed-profile",name="Default")
        every { primary.renewalCheckout } returns checkout
        every { checkout.state } returns MutableStateFlow(RenewalCheckoutState(message="Not eligible"))
        coEvery { activation.resolve("123456") } returns Result.failure(IllegalStateException("Still inactive"))
        val vm=XtreamGateViewModel(iptv,activation,profiles,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),primary)
        try {
            var navigated=0;vm.onActivationCodeChange("123456");vm.completeRenewal{navigated++};runCurrent()
            assertEquals(0,navigated);assertFalse(vm.uiState.value.renewalRequested)
            coVerify(exactly=1){activation.resolve("123456")}
            coVerify(exactly=0){iptv.saveVerifiedActivation(any(),any())}
            coVerify(exactly=0){iptv.verifyXtreamLogin(any(),any(),any())}
            coEvery { activation.resolve("123456") } returns Result.success(IptvActivationCredentials("fixture-user","fixture-pass"))
            coEvery { iptv.verifyXtreamLogin(any(),any(),any()) } returns XtreamLoginCheckResult(true)
            coEvery { iptv.loadSnapshot(any(),any(),any(),any(),any(),any(),any(),any()) } returns IptvSnapshot(channels=emptyList())
            vm.completeRenewal{navigated++};runCurrent()
            assertEquals(1,navigated)
            coVerify(exactly=1){iptv.saveVerifiedActivation(any(),any())}
        } finally {vm.viewModelScope.cancel();runCurrent();unmockkObject(AppBrand);Dispatchers.resetMain()}
    }
    @Test fun expiredLoginCanOpenCountryConfirmationWithoutIssuingQrOrGrantingAccess()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));mockkObject(AppBrand)
        every{AppBrand.id}returns "b".repeat(32)
        val activation=mockk<IptvActivationRepository>();val iptv=mockk<IptvRepository>(relaxed=true)
        val primary=mockk<PrimaryAccessRepository>();val checkout=mockk<RenewalCheckoutRepository>(relaxed=true)
        every{primary.renewalCheckout}returns checkout
        every{checkout.state}returns MutableStateFlow(RenewalCheckoutState(needsCountry=true))
        coEvery{activation.resolve("123456")}returns Result.failure(IllegalStateException("Inactive"))
        val vm=XtreamGateViewModel(iptv,activation,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),primary)
        try {
            var navigated=0;vm.onActivationCodeChange("123456");vm.submit{navigated++};runCurrent()
            assertTrue(vm.uiState.value.renewalRequested);assertNull(checkout.state.value.url);assertEquals(0,navigated)
            coVerify(exactly=0){iptv.saveVerifiedActivation(any(),any())}
        }finally{vm.viewModelScope.cancel();runCurrent();unmockkObject(AppBrand);Dispatchers.resetMain()}
    }
}
