package com.arflix.tv.access

import org.junit.Assert.*
import org.junit.Test

class PrimaryNoticePolicyTest {
    @Test fun firstActivationIsTheOnlyUnverifiedStateThatShowsTheCodeForm(){
        assertEquals(PrimaryGatePage.ACTIVATION,PrimaryAccessState(checking=false,bound=false).gatePage())
    }
    @Test fun previouslyActivatedInactiveAccountShowsProviderNoticeNotActivation(){
        assertEquals(PrimaryGatePage.ACCOUNT_INACTIVE,PrimaryAccessState(checking=false,bound=true,accountInactive=true).gatePage())
    }
    @Test fun boundAccountWithUnavailableValidationShowsConnectionNoticeNotExpiryOrActivation(){
        assertEquals(PrimaryGatePage.VERIFICATION_UNAVAILABLE,PrimaryAccessState(checking=false,bound=true).gatePage())
    }
    @Test fun validAccountAutomaticallyReturnsToAppContent(){
        assertEquals(PrimaryGatePage.CONTENT,PrimaryAccessState(checking=false,bound=true,primaryAllowed=true).gatePage())
    }
    @Test fun initialValidationDoesNotFlashTheActivationForm(){
        assertEquals(PrimaryGatePage.CHECKING,PrimaryAccessState(bound=true).gatePage())
    }
    @Test fun customerNoticesNeverExposeActivationTrialOrManualSyncControls(){
        val source=java.io.File("src/main/kotlin/com/arflix/tv/ui/components/PrimaryActivationGate.kt").readText()
        val notices=source.substringAfter("private fun AccountAccessNotice")
        assertTrue(notices.contains("Please contact your provider to renew or reactivate your account."))
        for(forbidden in listOf("XtreamGateScreen","TextButton(","Button(","onClick","activation code","TrialSignup"))assertFalse(forbidden,notices.contains(forbidden))
    }
}
