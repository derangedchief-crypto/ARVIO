package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvGuideViewportMemoryTest {
    @Test
    fun restoresEachCategoryWithoutOverwritingAnother() {
        val memory = TvGuideViewportMemory()
        memory.remember("provider|news", 48, 96)
        memory.remember("provider|sports", 0, 48)

        assertThat(memory.restore("provider|news")).isEqualTo(48 until 96)
        assertThat(memory.restore("provider|sports")).isEqualTo(0 until 48)
    }
}
