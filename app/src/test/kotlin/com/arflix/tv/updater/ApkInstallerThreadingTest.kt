package com.arflix.tv.updater

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.FileProvider
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=com.arflix.tv.SentryRegressionTestApplication::class,manifest=Config.NONE)
class ApkInstallerThreadingTest {
    @After fun cleanup(){unmockkAll()}
    private fun fixture(writeFailure:Exception?=null,closeFailure:Boolean=false,cancel:Boolean=false)=runBlocking {
        val context=mockk<Context>(relaxed=true)
        val pm=mockk<PackageManager>(relaxed=true)
        val installer=mockk<PackageInstaller>(relaxed=true)
        val session=mockk<PackageInstaller.Session>(relaxed=true)
        every{context.packageManager}returns pm
        every{context.applicationContext}returns context
        every{context.packageName}returns "fixture.package"
        every{pm.packageInstaller}returns installer
        mockkStatic(FileProvider::class)
        every{FileProvider.getUriForFile(any(),any(),any())}throws IllegalArgumentException("No fixture provider")
        fun offMain(){assertNotEquals("PackageInstaller/disk work must not block UI",Looper.getMainLooper().thread,Thread.currentThread())}
        every{installer.createSession(any())}answers{offMain();42}
        every{installer.openSession(42)}answers{offMain();session}
        val copied=ByteArrayOutputStream()
        every{session.openWrite(any(),any(),any())}answers{offMain();object:OutputStream(){
            override fun write(value:Int){offMain();if(writeFailure!=null)throw writeFailure;copied.write(value)}
            override fun write(bytes:ByteArray,offset:Int,length:Int){offMain();if(writeFailure!=null)throw writeFailure;copied.write(bytes,offset,length)}
        }}
        every{session.fsync(any())}answers{offMain();Unit}
        every{session.commit(any())}answers{offMain();Unit}
        if(closeFailure)every{session.close()}throws IOException("Close failure")
        mockkObject(UpdateRelaunch)
        every{UpdateRelaunch.createStatusCallback(any(),42)}returns mockk<PendingIntent>(relaxed=true)
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"installer-thread-fixture.apk").apply{writeBytes(ByteArray(65536){it.toByte()})}
        if(cancel){
            try{ApkInstaller.launchInstall(context,file);fail("Cancellation must propagate")}
            catch(_:CancellationException){}
            verify(exactly=1){installer.abandonSession(42)}
            verify(exactly=0){session.commit(any())}
        }else{
            val accepted=ApkInstaller.launchInstall(context,file)
            if(writeFailure==null){
                assertTrue(accepted);assertArrayEquals(file.readBytes(),copied.toByteArray())
                verify(exactly=1){session.commit(any())}
                verify(exactly=0){installer.abandonSession(any())}
            }else{
                verify(exactly=1){installer.abandonSession(42)}
                verify(exactly=0){session.commit(any())}
            }
        }
        verify(exactly=1){session.close()}
    }
    @Test fun sessionCreationCopyFsyncCommitAndCloseRunOffMain()=fixture()
    @Test fun failedCopyClosesAndAbandonsTheSession()=fixture(writeFailure=IOException("Disk failure"))
    @Test fun closeFailureAfterCommitDoesNotLaunchAnotherInstaller()=fixture(closeFailure=true)
    @Test fun cancelledCopyAbandonsInsteadOfOpeningFallback()=fixture(writeFailure=CancellationException("Cancelled"),cancel=true)
}
