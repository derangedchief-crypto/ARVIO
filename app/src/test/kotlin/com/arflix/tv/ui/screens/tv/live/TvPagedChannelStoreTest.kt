package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvPagedChannelStoreTest {
    @Test
    fun usesFullIndexWhenItExceedsBoundedStartupSnapshot() {
        assertThat(shouldUsePagedChannelStore(snapshotChannelCount = 240, indexedChannelCount = 4_000)).isTrue()
    }

    @Test
    fun doesNotPageWhenSnapshotAlreadyContainsTheWholeProvider() {
        assertThat(shouldUsePagedChannelStore(snapshotChannelCount = 4_000, indexedChannelCount = 4_000)).isFalse()
    }
}
