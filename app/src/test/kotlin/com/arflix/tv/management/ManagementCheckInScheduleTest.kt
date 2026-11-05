package com.arflix.tv.management

import org.junit.Assert.*
import org.junit.Test

class ManagementCheckInScheduleTest {
    @Test fun oldServerCannotPutVisibleAppOnFiveMinutePolling() {
        assertEquals(15_000L, managementCheckInDelay(300, false))
        assertEquals(30_000L, managementCheckInDelay(300, true))
        assertEquals(15_000L, managementCheckInDelay(15, true))
        assertEquals(15_000L, managementCheckInDelay(0, false))
    }

    @Test fun pushAndNetworkBurstsStayBelowServerTwentyCheckInsPerMinuteLimit() {
        var last = -MANAGEMENT_MIN_CHECK_IN_MS
        var starts = 0
        for (now in 0L until 60_000L step 100) {
            if (managementCheckInRemaining(now, last) == 0L) { last = now; starts++ }
        }
        assertEquals(15, starts)
        assertEquals(4_000L, managementCheckInRemaining(last, last))
    }

    @Test fun reopeningCanCheckInImmediatelyWithoutWaitingForOldInterval() {
        assertEquals(0L, managementCheckInRemaining(800_000L, 796_000L))
    }

    @Test fun liveIdentityTracksCredentialRotationWithoutReplacingInstallation() {
        val saved = ManagementSession("a".repeat(64), "b".repeat(32), deviceId="c".repeat(32), credential="d".repeat(64))
        val rotated = saved.copy(credential="e".repeat(64))
        assertEquals(managementLiveIdentity(saved)?.deviceId, managementLiveIdentity(rotated)?.deviceId)
        assertNotEquals(managementLiveIdentity(saved), managementLiveIdentity(rotated))
        assertEquals(saved.installationId, rotated.installationId)
        assertNull(managementLiveIdentity(saved.copy(credential=null)))
    }
}
