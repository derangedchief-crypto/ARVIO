package com.arflix.tv.ui.screens.tv.live

import android.view.SurfaceHolder
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class PlayerSurfaceAvailabilityTest {
    @Test fun sameViewBecomingReadyWakesBindingAndLateDisposedCallbacksDoNothing() {
        val holder = mockk<SurfaceHolder>(relaxed = true)
        val slot = slot<SurfaceHolder.Callback>()
        every { holder.addCallback(capture(slot)) } just Runs
        var changes = 0
        val observer = PlayerSurfaceAvailabilityObserver { changes++ }
        observer.observe(holder); observer.observe(holder)
        verify(exactly = 1) { holder.addCallback(any()) }
        slot.captured.surfaceCreated(holder)
        slot.captured.surfaceChanged(holder, 0, 200, 112)
        slot.captured.surfaceDestroyed(holder)
        assertEquals(3, changes)
        observer.close()
        verify(exactly = 1) { holder.removeCallback(observer) }
        slot.captured.surfaceCreated(holder)
        assertEquals(3, changes)
    }

    @Test fun outgoingFullscreenLayerLivesUntilPreviewOwnsTheOutput() {
        assertTrue(keepFullscreenPlayerLayer(true, true, 0f, false))
        assertTrue(keepFullscreenPlayerLayer(true, false, 0.4f, false))
        assertTrue(keepFullscreenPlayerLayer(true, false, 0f, true))
        assertFalse(keepFullscreenPlayerLayer(true, false, 0f, false))
        assertFalse(keepFullscreenPlayerLayer(false, true, 1f, true))
    }
}
