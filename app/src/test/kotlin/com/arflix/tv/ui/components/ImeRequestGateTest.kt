package com.arflix.tv.ui.components

import org.junit.Assert.*
import org.junit.Test

class ImeRequestGateTest {
    @Test fun overlappingRequestsAreCoalescedButLaterUserRetryWorks() {
        val gate=ImeRequestGate()
        assertTrue(gate.allow(true,true,true,1000))
        assertFalse(gate.allow(true,true,true,1100))
        assertFalse(gate.allow(true,true,true,1200))
        assertTrue(gate.allow(true,true,true,1500))
    }
    @Test fun dismissedBackgroundOrUnfocusedEditorsNeverRequestIme() {
        val gate=ImeRequestGate()
        assertFalse(gate.allow(false,true,true,1000))
        assertFalse(gate.allow(true,false,true,1000))
        assertFalse(gate.allow(true,true,false,1000))
        assertTrue(gate.allow(true,true,true,1000))
    }
    @Test fun editorsDoNotShareThrottleState() {
        assertTrue(ImeRequestGate().allow(true,true,true,1000))
        assertTrue(ImeRequestGate().allow(true,true,true,1000))
    }
}
