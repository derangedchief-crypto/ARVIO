package com.arflix.tv.management

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagementMaintenanceTest {
    @Test fun cacheUsesOnlyOwnerCallbacksAndReportsBothClasses() {
        val calls = mutableListOf<String>()
        val report = clearManagementCacheOwners({ calls += "image" }, { calls += "http" })
        assertEquals(listOf("image", "http"), calls)
        assertTrue(report.succeeded)
        assertTrue(report.summary.contains("update files and playback buffers untouched"))
    }
    @Test fun cachePartialFailureStillAttemptsOtherOwnerAndNeverEchoesErrors() {
        var http = false
        val report = clearManagementCacheOwners({ error("secret-password private-path") }, { http = true })
        assertTrue(http); assertFalse(report.succeeded)
        assertFalse(report.summary.contains("secret-password"))
        assertTrue(report.summary.contains("Image cache: unavailable"))
    }
    @Test fun reopenRequiresCurrentEligibleForegroundHostAndUnbindIsIdentityScoped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var eligible = false; var reopened = 0
        val host = object : ManagementForegroundHost {
            override fun canReopen() = eligible
            override fun reopen(): Boolean { reopened++; return true }
        }
        try {
            ManagementForeground.bind(host)
            assertFalse(ManagementForeground.eligible()); assertFalse(ManagementForeground.reopen())
            eligible = true; assertTrue(ManagementForeground.eligible()); assertTrue(ManagementForeground.reopen())
            assertEquals(1, reopened)
            ManagementForeground.unbind(host)
            assertFalse(ManagementForeground.eligible()); assertFalse(ManagementForeground.reopen())
            assertEquals(1, reopened)
        } finally { ManagementForeground.unbind(host); Dispatchers.resetMain() }
    }
}
