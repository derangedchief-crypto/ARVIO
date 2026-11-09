package com.arflix.tv.util

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicDownloadedFileTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `first use clears only interrupted downloads and does not remove active staging`() {
        val stale = File(temp.root, "download-interrupted.pending").apply { writeText("partial") }
        val other = File(temp.root, "user.pending").apply { writeText("keep") }
        replaceDownloadedFile(File(temp.root, "new.json")) { output ->
            assertFalse(stale.exists())
            val active = temp.root.listFiles()!!.single { it.name.startsWith("download-") }
            cleanInterruptedDownloads(temp.root)
            assertTrue(active.exists())
            output.write("complete".toByteArray())
        }
        assertTrue(other.exists())
    }
    @Test fun `complete replacement retires old bytes without accumulating files`() {
        val target = File(temp.root, "guide.json").apply { writeText("old") }
        repeat(4) { generation ->
            replaceDownloadedFile(target) {
                assertTrue(target.isFile)
                it.write("new-$generation".toByteArray())
            }
            assertEquals("new-$generation", target.readText())
            assertEquals(listOf("guide.json"), temp.root.list()!!.toList())
        }
    }
    @Test fun `disk failure and cancellation preserve last good copy and remove partials`() {
        val target = File(temp.root, "guide.json").apply { writeText("old") }
        for (error in listOf(IOException("full"), CancellationException("cancelled"))) {
            try {
                replaceDownloadedFile(target) { it.write("partial".toByteArray()); throw error }
                fail("Must propagate")
            } catch (actual: Exception) { assertSame(error, actual) }
            assertEquals("old", target.readText())
            assertEquals(listOf("guide.json"), temp.root.list()!!.toList())
        }
    }
}
