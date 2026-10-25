package com.arflix.tv.trials

import org.junit.Assert.*
import org.junit.Test

class TrialVisibilityTest {
    @Test fun `an unavailable offer hides new signup controls`() {
        val off=TrialSignupUiState(available=false,formOpen=true)
        assertFalse(off.showSection);assertFalse(off.showNewTrialButton)
    }
    @Test fun `an available offer exposes the new trial button`() {
        val on=TrialSignupUiState(available=true)
        assertTrue(on.showSection);assertTrue(on.showNewTrialButton)
    }
    @Test fun `pausing an offer preserves recovery without exposing a new signup`() {
        val pending=TrialSignupUiState(available=false,pending=true)
        assertTrue(pending.showSection);assertFalse(pending.showNewTrialButton)
    }
    @Test fun `already created activation handoff remains visible when offers turn off`() {
        val created=TrialSignupUiState(available=false,activationCode="123456")
        assertTrue(created.showSection);assertFalse(created.showNewTrialButton)
    }
    @Test fun `work already underway remains visible without offering another signup`() {
        val working=TrialSignupUiState(available=false,busy=true)
        assertTrue(working.showSection);assertFalse(working.showNewTrialButton)
    }
}
