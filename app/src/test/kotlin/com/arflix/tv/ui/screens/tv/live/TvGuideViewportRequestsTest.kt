package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvGuideViewportRequestsTest {
    @Test
    fun onlyNewestViewportResultMayBeDelivered() {
        val gate = TvGuideViewportRequestGate()
        val first = gate.beginRequest()
        val second = gate.beginRequest()

        assertThat(gate.isCurrent(first)).isFalse()
        assertThat(gate.isCurrent(second)).isTrue()
    }
}
