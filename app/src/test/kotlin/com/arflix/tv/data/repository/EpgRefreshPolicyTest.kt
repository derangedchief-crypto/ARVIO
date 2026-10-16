package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgRefreshPolicyTest {
    @Test
    fun reusesFreshGuideForQueuedBackgroundWork() {
        assertThat(
            shouldReuseFreshBackgroundGuide(
                isBackgroundWork = true,
                requestedForcedRefresh = true,
                cachedEpgAtMs = 1_000L,
                indexedProgramCount = 20,
                nowMs = 2_000L,
            ),
        ).isTrue()
    }

    @Test
    fun doesNotSkipAStaleOrMissingGuide() {
        assertThat(
            shouldReuseFreshBackgroundGuide(
                isBackgroundWork = true,
                requestedForcedRefresh = true,
                cachedEpgAtMs = 1L,
                indexedProgramCount = 0,
                nowMs = 7L * 60L * 60_000L,
            ),
        ).isFalse()
    }
}
