package com.arflix.tv.management

import org.junit.Assert.*
import org.junit.Test

class ManagementRecoveryStatusTest {
    @Test fun permanentHoldsDoNotPromiseAutomaticRecoveryInMinutes() {
        assertTrue(managementRecoveryStatus("unidentified").contains("Provider approval needed"))
        assertTrue(managementRecoveryStatus("ambiguous").contains("Provider review needed"))
    }
    @Test fun busyAndRestrictedStatesRemainDistinct() {
        assertTrue(managementRecoveryStatus("busy").contains("previous installation"))
        assertTrue(managementRecoveryStatus("restricted").contains("restricted"))
        assertTrue(managementRecoveryStatus("owner_authorized").contains("completing signed reconnection"))
        assertEquals("Checking previous device identity",managementRecoveryStatus("future-state"))
    }
}
