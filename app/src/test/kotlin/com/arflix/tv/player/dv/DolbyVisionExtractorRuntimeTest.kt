package com.arflix.tv.player.dv

import android.app.Application
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import com.arflix.tv.player.dvmkv.DolbyVisionCompatibility
import com.arflix.tv.player.dvmkv.MatroskaExtractor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DolbyVisionExtractorRuntimeTest {
    @After fun reset() { DolbyVisionCompatibility.setHdr10BaseLayerModeActive(false) }

    @Test fun enabledFactoryStillUsesDvHooksAndPreservesOtherExtractors() {
        val stock = DefaultExtractorsFactory().createExtractors()
        val wrapped = DolbyVisionStripExtractorsFactory(ExtractorsFactory { stock }).createExtractors()
        assertEquals(stock.size, wrapped.size)
        assertTrue(DolbyVisionCompatibility.isHdr10BaseLayerModeActive())
        stock.indices.forEach { index ->
            if (stock[index].javaClass.name == "androidx.media3.extractor.mkv.MatroskaExtractor") {
                assertTrue(wrapped[index] is MatroskaExtractor)
                assertNotNull((wrapped[index] as MatroskaExtractor).dolbyVisionSampleTransformer)
            } else assertSame(stock[index], wrapped[index])
        }
        assertTrue(wrapped.any { it is MatroskaExtractor })
    }

    @Test fun disabledFactoryRetainsAllStockExtractorInstances() {
        val stock = DefaultExtractorsFactory().createExtractors()
        val wrapped = DolbyVisionStripExtractorsFactory(ExtractorsFactory { stock }, enabledProvider = { false })
            .createExtractors()
        stock.indices.forEach { assertSame(stock[it], wrapped[it]) }
        assertFalse(DolbyVisionCompatibility.isHdr10BaseLayerModeActive())
    }
}
