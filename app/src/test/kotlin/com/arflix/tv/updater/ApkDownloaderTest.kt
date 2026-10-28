package com.arflix.tv.updater

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=com.arflix.tv.SentryRegressionTestApplication::class,manifest=Config.NONE)
class ApkDownloaderTest {
    @Test fun calledFromUiDownloadsOffMainWithBoundedProgressAndExactFinalBytes()=runBlocking {
        val bytes=ByteArray(3*1024*1024){it.toByte()}
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            assertNotEquals(Looper.getMainLooper().thread,Thread.currentThread())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(object:ResponseBody(){
                    override fun contentType():okhttp3.MediaType?=null
                    override fun contentLength()=bytes.size.toLong()
                    override fun source()=Buffer().write(bytes)
                }).build()
        }.build()
        val context=RuntimeEnvironment.getApplication()
        val destination=File(context.cacheDir,"download-progress-fixture.apk")
        val progress=mutableListOf<Long>()
        val result=ApkDownloader(context,client).download("https://fixture.example/update.apk",destination){downloaded,total ->
            assertNotEquals(Looper.getMainLooper().thread,Thread.currentThread())
            assertEquals(bytes.size.toLong(),total);progress.add(downloaded)
        }
        assertTrue(result.isSuccess);assertArrayEquals(bytes,destination.readBytes())
        assertEquals(0L,progress.first());assertEquals(bytes.size.toLong(),progress.last())
        assertTrue("Must not emit one progress update per disk buffer",progress.size<bytes.size/8192/2)
    }
    @Test fun truncatedResponseIsNotReportedReadyForInstallation()=runBlocking {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(object:ResponseBody(){
                    override fun contentType():okhttp3.MediaType?=null
                    override fun contentLength()=100L
                    override fun source()=Buffer().writeUtf8("partial")
                }).build()
        }.build()
        val context=RuntimeEnvironment.getApplication()
        val result=ApkDownloader(context,client).download("https://fixture.example/update.apk",File(context.cacheDir,"truncated-fixture.apk")){_,_->}
        assertTrue(result.isFailure)
    }
}
