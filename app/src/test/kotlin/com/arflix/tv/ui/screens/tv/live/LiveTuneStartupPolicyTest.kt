package com.arflix.tv.ui.screens.tv.live

import org.junit.Assert.*
import org.junit.Test

class LiveTuneStartupPolicyTest {
    @Test fun normalSelectionDoesNotWaitButHeldZapKeysStillCoalesce() {
        assertEquals(0L, liveTuneDebounceMs(null, 1000L))
        assertEquals(0L, liveTuneDebounceMs(1000L, 2000L))
        assertEquals(250L, liveTuneDebounceMs(1000L, 1100L))
        assertEquals(250L, liveTuneDebounceMs(1100L, 1200L))
    }
    @Test fun lowerFirstPictureThresholdRetainsRecoveryDepthAndHeapSafety() {
        listOf(256 to true, 384 to false, 1024 to false).forEach { (memory, lowRam) ->
            val profile = buildLiveTvBufferProfile(memory, lowRam)
            assertEquals(750, profile.bufferForPlaybackMs)
            assertEquals(20_000, profile.minBufferMs)
            assertEquals(45_000, profile.maxBufferMs)
            assertTrue(profile.bufferForPlaybackAfterRebufferMs >= 3_500)
            assertEquals((if (lowRam || memory <= 384) 48 else 64) * 1024 * 1024, profile.targetBufferBytes)
            assertEquals(5_000, profile.backBufferMs)
        }
    }
}
