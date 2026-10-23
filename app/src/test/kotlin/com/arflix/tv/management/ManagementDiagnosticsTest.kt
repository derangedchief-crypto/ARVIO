package com.arflix.tv.management

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ManagementDiagnosticsTest {
    private val context = mockk<Context>()
    private val connectivity = mockk<ConnectivityManager>()
    private val network = mockk<Network>()
    private val caps = mockk<NetworkCapabilities>()
    private fun setup() {
        every { context.getSystemService(ActivityManager::class.java) } returns null
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns caps
        every { connectivity.isActiveNetworkMetered } returns false
        every { caps.hasCapability(any()) } returns false
        every { caps.hasTransport(any()) } returns false
    }
    @Test fun validatedWifiIsAnEnumNotNetworkIdentity() {
        setup()
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        val result = managementRuntimeDiagnostics(context)
        assertEquals("validated", result["network_state"])
        assertEquals("wifi", result["network_transport"])
        assertEquals("no", result["network_metered"])
        assertEquals("unknown", result["memory_state"])
        assertEquals(setOf("diagnostics_version", "heap_used", "heap_limit", "memory_state", "network_state", "network_transport", "network_metered"), result.keys)
        assertTrue(result.getValue("heap_used").toLong() <= result.getValue("heap_limit").toLong())
    }
    @Test fun vpnWinsOverUnderlyingTransportAndUnvalidatedIsNotOnlineSuccess() {
        setup()
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns true
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        assertEquals("vpn", managementRuntimeDiagnostics(context)["network_transport"])
        assertEquals("unvalidated", managementRuntimeDiagnostics(context)["network_state"])
    }
    @Test fun offlineAndMissingCapabilitiesAreDistinct() {
        setup()
        every { connectivity.activeNetwork } returns null
        assertEquals("offline", managementRuntimeDiagnostics(context)["network_state"])
        assertEquals("none", managementRuntimeDiagnostics(context)["network_transport"])
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns null
        assertEquals("unknown", managementRuntimeDiagnostics(context)["network_state"])
    }
    @Test fun permissionDeniedAndPlatformErrorsNeverLeakExceptionText() {
        setup()
        every { connectivity.activeNetwork } throws SecurityException("secret SSID password https://private.test")
        val result = managementRuntimeDiagnostics(context)
        assertEquals("permission_denied", result["network_state"])
        assertFalse(result.toString().contains("secret"))
        every { connectivity.activeNetwork } throws IllegalStateException("private-address")
        assertEquals("unknown", managementRuntimeDiagnostics(context)["network_state"])
    }
    @Test fun lowMemoryIsReportedWithoutProcessInventory() {
        setup()
        val manager = mockk<ActivityManager>()
        every { context.getSystemService(ActivityManager::class.java) } returns manager
        every { manager.getMemoryInfo(any()) } answers { firstArg<ActivityManager.MemoryInfo>().lowMemory = true }
        assertEquals("low", managementRuntimeDiagnostics(context)["memory_state"])
    }
}
