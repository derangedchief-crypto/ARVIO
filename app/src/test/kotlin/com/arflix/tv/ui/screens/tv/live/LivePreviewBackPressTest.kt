package com.arflix.tv.ui.screens.tv.live

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LivePreviewBackPressTest {
    @Test fun fullscreenBackReleaseCannotNavigateGuideAgainAfterLogicalStateChanges() {
        val guard = LiveBackReleaseGuard()
        assertFalse(guard.consumeDown(repeat = false))
        guard.handledDown()
        assertTrue(guard.consumeDown(repeat = true))
        assertTrue(guard.consumeUp())
        assertFalse(guard.consumeUp())
        assertFalse(guard.consumeDown(repeat = false))
    }
    @Test fun freshBackPressClearsAnAbandonedReleaseGuard() {
        val guard = LiveBackReleaseGuard()
        guard.handledDown()
        assertFalse(guard.consumeDown(repeat = false))
        assertFalse(guard.consumeUp())
    }
    @Test fun quickBackKeepsShortActionAndDoesNotPromote() = runTest {
        val press = LivePreviewBackPress(backgroundScope)
        var short = 0; var long = 0
        assertTrue(press.down({ true }, { short++ }, { long++ }))
        advanceTimeBy(100)
        assertTrue(press.up())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, short); assertEquals(0, long)
    }
    @Test fun heldBackPromotesOnceAndConsumesRepeatsAndReleaseAfterFullscreen() = runTest {
        val press = LivePreviewBackPress(backgroundScope)
        var enabled = true; var short = 0; var long = 0
        val promote = { long++; enabled = false }
        press.down({ enabled }, { short++ }, promote)
        advanceTimeBy(601); runCurrent()
        assertEquals(1, long)
        repeat(3) { assertTrue(press.down({ enabled }, { short++ }, promote)) }
        assertTrue(press.up())
        assertEquals(0, short); assertEquals(1, long)
        assertFalse(press.down({ enabled }, { short++ }, promote))
        enabled = true
        assertTrue(press.down({ enabled }, { short++ }, promote))
        advanceTimeBy(601); runCurrent()
        assertTrue(press.up())
        assertEquals(2, long); assertEquals(0, short)
    }
    @Test fun noPlayingPreviewLeavesBackAloneAndCancellationDoesNotNavigate() = runTest {
        val press = LivePreviewBackPress(backgroundScope)
        var actions = 0
        assertFalse(press.down({ false }, { actions++ }, { actions++ }))
        assertFalse(press.up())
        press.down({ true }, { actions++ }, { actions++ })
        press.cancel(); advanceTimeBy(1_000); runCurrent()
        assertEquals(0, actions)
        press.down({ true }, { actions++ }, { actions++ })
        assertTrue(press.up(cancelled = true))
        assertEquals(0, actions)
    }
}
