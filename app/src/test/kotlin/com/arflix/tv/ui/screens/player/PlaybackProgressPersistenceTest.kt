package com.arflix.tv.ui.screens.player

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackProgressPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `failed atomic progress save preserves state and next tick recovers`() = runTest {
        var diskFull = false
        val serializer = object : Serializer<Int> {
            override val defaultValue = 0
            override suspend fun readFrom(input: InputStream) = input.read()
            override suspend fun writeTo(t: Int, output: OutputStream) {
                output.write(t)
                if (diskFull) throw IOException("ENOSPC")
            }
        }
        val store = DataStoreFactory.create(serializer, scope = backgroundScope) {
            temp.root.resolve("progress")
        }
        store.updateData { 10 }
        diskFull = true
        var failures = 0
        var cloudPublished = false
        persistPlaybackProgress({ failures++ }) {
            store.updateData { 20 }
            cloudPublished = true
        }
        assertEquals(1, failures)
        assertFalse(cloudPublished)
        assertEquals(10, store.data.first())
        assertEquals(10, temp.root.resolve("progress").inputStream().use { it.read() })
        diskFull = false
        persistPlaybackProgress({ failures++ }) { store.updateData { 30 } }
        assertEquals(30, store.data.first())
        assertEquals(1, failures)
    }

    @Test fun `cancellation and programming errors are not storage failures`() = runTest {
        for (error in listOf(CancellationException("stopped"), IllegalStateException("bug"))) {
            try {
                persistPlaybackProgress({ fail("Must not report storage failure") }) { throw error }
                fail("Must propagate")
            } catch (actual: Exception) {
                assertSame(error, actual)
            }
        }
    }
}
