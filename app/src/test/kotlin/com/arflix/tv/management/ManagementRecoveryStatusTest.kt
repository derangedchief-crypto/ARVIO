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

    @Test fun validatedRecoveredApprovalClearsOnlySyntheticHoldUsingCurrentServerPolicy() {
        val held=ManagementSession("a".repeat(64),"b".repeat(32),policy="suspended",recoveryPending=true)
        val active=approvedRecoveryPolicy(held,"approved","recovered","active")
        assertEquals("active",active.policy);assertFalse(active.recoveryPending)
        assertEquals(held.installationId,active.installationId)
        for(policy in listOf("blocked","suspended","removed")){
            val restricted=approvedRecoveryPolicy(held,"approved","recovered",policy)
            assertEquals(policy,restricted.policy);assertFalse(restricted.recoveryPending)
        }
        for(status in listOf("pending","rejected"))assertEquals(held,approvedRecoveryPolicy(held,status,"recovered","active"))
        for(state in listOf("unidentified","busy","ambiguous",""))assertEquals(held,approvedRecoveryPolicy(held,"approved",state,"active"))
        assertEquals(held,approvedRecoveryPolicy(held,"approved","recovered","unexpected"))
    }
}
