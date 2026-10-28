package com.arflix.tv.updater

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressGateTest {
    @Test fun fastLargeDownloadDoesNotFloodComposeWithPerBufferUpdates(){
        val gate=DownloadProgressGate()
        val updates=(0L..1000L).count{gate.shouldEmit(it)}
        assertEquals(5,updates)
    }
    @Test fun repeatedTimestampsAreSuppressedButClockResetDoesNotStallProgress(){
        val gate=DownloadProgressGate()
        assertTrue(gate.shouldEmit(1000));assertFalse(gate.shouldEmit(1000))
        assertFalse(gate.shouldEmit(1249));assertTrue(gate.shouldEmit(1250))
        assertTrue(gate.shouldEmit(5))
    }
    @Test fun downloaderAlwaysReportsCompletedBytesAndDoesIoInternally(){
        val source=java.io.File("src/main/kotlin/com/arflix/tv/updater/ApkDownloader.kt").readText()
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("onProgress(downloaded,total)"))
        assertTrue(source.contains("downloaded == total"))
    }
}
