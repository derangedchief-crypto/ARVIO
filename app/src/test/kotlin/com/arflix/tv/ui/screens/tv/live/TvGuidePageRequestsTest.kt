package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvGuidePageRequestsTest {
    @Test
    fun coalescesHeldDirectionButAllowsReverseImmediately() {
        val gate = TvGuidePageRequestGate(minIntervalMs = 180L)

        assertThat(gate.shouldRequest(TvGuideDirection.DOWN, 1_000L)).isTrue()
        assertThat(gate.shouldRequest(TvGuideDirection.DOWN, 1_100L)).isFalse()
        assertThat(gate.shouldRequest(TvGuideDirection.UP, 1_101L)).isTrue()
    }
}
