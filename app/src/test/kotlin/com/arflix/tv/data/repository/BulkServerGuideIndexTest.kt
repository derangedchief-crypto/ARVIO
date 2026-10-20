package com.arflix.tv.data.repository

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BulkServerGuideIndexTest {
    @Test fun bulkImports96000RowsIn16BatchesAndKeepsUnreturnedGuide() = runBlocking {
        fixture { index, file, now ->
            val old = IptvProgram("Old title", startUtcMillis = now, endUtcMillis = now + 3_600_000)
            val future = old.copy(title = "Unreturned future", startUtcMillis = now + 70 * 3_600_000L, endUtcMillis = now + 71 * 3_600_000L)
            index.mergeChannels("source", mapOf("untouched" to IptvNowNext(now = old),
                "channel-0" to IptvNowNext(now = old, upcoming = listOf(future))), now)
            val generation = index.sourceState("source")!!.generation
            var batches = 0
            val started = System.nanoTime()
            index.beginRefresh("source")
            index.mergeServerSnapshot("source", file, (0 until 1000).map { "c$it" to "channel-$it" },
                now - 48 * 3_600_000L, now + 72 * 3_600_000L, now) { _, _ ->
                batches++
                assertTrue(index.loadWindow("source", setOf("untouched"), now, now + 3_600_000).isNotEmpty())
            }
            assertEquals(16, batches)
            assertEquals(96_002, index.countPrograms("source"))
            assertEquals(generation, index.sourceState("source")!!.generation)
            val rows = index.loadWindow("source", setOf("channel-0"), now, now + 72 * 3_600_000L).getValue("channel-0")
            assertTrue(rows.any { it.title == "p-47" })
            assertTrue(rows.any { it.title == "Unreturned future" })
            assertFalse(rows.any { it.title == "Old title" })
            System.err.println("[EPG-Bulk] programmes=96000 channels=1000 commits=$batches time_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
    @Test fun cancelledImportKeepsPreviousRowsAndCommittedBatches() = runBlocking {
        fixture { index, file, now ->
            index.mergeChannels("source", mapOf("untouched" to IptvNowNext(now = IptvProgram("Saved", startUtcMillis = now, endUtcMillis = now + 3_600_000))), now)
            try {
                index.mergeServerSnapshot("source", file, (0 until 1000).map { "c$it" to "channel-$it" },
                    now - 48 * 3_600_000L, now + 72 * 3_600_000L, now) { _, _ -> throw CancellationException("Interrupted") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertTrue(index.loadWindow("source", setOf("untouched"), now, now + 3_600_000).isNotEmpty())
            assertTrue(index.countPrograms("source") > 1)
            // Attachment/temp-table cleanup permits the next refresh.
            index.mergeServerSnapshot("source", file, listOf("c999" to "last-channel"), now, now + 3_600_000, now)
            assertTrue(index.loadWindow("source", setOf("last-channel"), now, now + 3_600_000).isNotEmpty())
        }
    }
    @Test fun failedReplacementRollsBackWithoutAnEmptyGuideWindow() = runBlocking {
        fixture { index, valid, now ->
            index.mergeChannels("source", mapOf("loaded" to IptvNowNext(now = IptvProgram("Saved programme", startUtcMillis = now, endUtcMillis = now + 3_600_000))), now)
            val invalid = File.createTempFile("invalid-guide-fixture", ".sqlite", RuntimeEnvironment.getApplication().cacheDir)
            try {
                SQLiteDatabase.openOrCreateDatabase(invalid, null).use { db ->
                    db.execSQL("CREATE TABLE programmes(channel_id TEXT,start_ms INTEGER,end_ms INTEGER,title TEXT)")
                    db.execSQL("INSERT INTO programmes VALUES('c0',?,?,'Replacement')", arrayOf<Any>(now, now + 3_600_000))
                }
                try {
                    index.mergeServerSnapshot("source", invalid, listOf("c0" to "loaded"), now, now + 3_600_000, now)
                    fail("Invalid insert must fail")
                } catch (_: android.database.sqlite.SQLiteException) { }
                assertEquals("Saved programme", index.loadWindow("source", setOf("loaded"), now, now + 3_600_000).getValue("loaded").single().title)
                index.mergeServerSnapshot("source", valid, listOf("c0" to "loaded"), now, now + 3_600_000, now)
                assertEquals("p-47", index.loadWindow("source", setOf("loaded"), now, now + 3_600_000).getValue("loaded").single().title)
            } finally { SQLiteDatabase.deleteDatabase(invalid) }
        }
    }
    private suspend fun fixture(block: suspend (IptvEpgIndex, File, Long) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_epg_index.db")
        val index = IptvEpgIndex(context)
        val now = System.currentTimeMillis()
        val file = File.createTempFile("bulk-guide-fixture", ".sqlite", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE programmes(channel_id TEXT,start_ms INTEGER,end_ms INTEGER,title TEXT,description TEXT)")
                db.execSQL("CREATE INDEX ix_programmes ON programmes(channel_id,start_ms,end_ms)")
                db.execSQL("""WITH RECURSIVE channels(c) AS (SELECT 0 UNION ALL SELECT c+1 FROM channels WHERE c<999),
                    hours(h) AS (SELECT 0 UNION ALL SELECT h+1 FROM hours WHERE h<95)
                    INSERT INTO programmes SELECT 'c'||c,?+h*3600000,?+(h+1)*3600000,'p-'||h,'Server programme'
                    FROM channels CROSS JOIN hours""", arrayOf<Any>(now - 47 * 3_600_000L, now - 47 * 3_600_000L))
            }
            block(index, file, now)
        } finally { index.close(); SQLiteDatabase.deleteDatabase(file) }
    }
}
