package com.arflix.tv.util

import android.app.Application
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=com.arflix.tv.SentryRegressionTestApplication::class,manifest=Config.NONE)
class LegacyWebpGuardTest {
    @After fun cleanup(){unmockkAll()}
    @Test fun policyIsRestrictedToLegacyAmazonPlatforms(){
        assertTrue(LegacyWebpGuard.isAffectedPlatform(28,"Amazon"))
        assertFalse(LegacyWebpGuard.isAffectedPlatform(29,"Amazon"))
        assertFalse(LegacyWebpGuard.isAffectedPlatform(28,"Google"))
        assertFalse(LegacyWebpGuard.isAffectedPlatform(28,"onn"))
    }
    @Test fun byteSniffingDoesNotTrustMisleadingMimeTypesOrFileNames(){
        assertTrue(LegacyWebpGuard.isWebp("RIFFxxxxWEBPdata".toByteArray()))
        assertFalse(LegacyWebpGuard.isWebp("GIF89a".toByteArray()))
        assertFalse(LegacyWebpGuard.isWebp(ByteArray(10)))
        assertFalse(LegacyWebpGuard.isWebp("RIFFxxxxWAVEdata".toByteArray()))
    }
    @Test fun unsafeWebpIsRejectedBeforeNativeDecodeWithoutConsumingSource()=runBlocking {
        ShadowBuild.setManufacturer("Amazon")
        val original="RIFFxxxxWEBPdata".toByteArray();val buffer=Buffer().write(original)
        val source=mockk<ImageSource>();every{source.source()}returns buffer
        val decoder=LegacyWebpGuard().create(SourceResult(source,"image/png",DataSource.NETWORK),mockk<Options>(),mockk<ImageLoader>())
        assertNotNull(decoder);assertArrayEquals(original,buffer.snapshot().toByteArray())
        try{decoder!!.decode();fail("Must not enter native WebP") }catch(_:java.io.IOException){}
    }
    @Test fun guardIsInstalledBeforeOtherDecodersInBothLoaderPaths(){
        for(path in listOf("ArflixApplication.kt","network/OkHttpProvider.kt")){
            val source=java.io.File("src/main/kotlin/com/arflix/tv/$path").readText()
            assertTrue(source.indexOf("LegacyWebpGuard()")>=0)
            assertTrue(source.indexOf("LegacyWebpGuard()")<source.indexOf("add(SvgDecoder.Factory())"))
        }
    }
}
