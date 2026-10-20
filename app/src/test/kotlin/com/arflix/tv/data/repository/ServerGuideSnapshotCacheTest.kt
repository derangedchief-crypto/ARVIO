package com.arflix.tv.data.repository

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ServerGuideSnapshotCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun payload(title: String): ByteArray {
        val file = File.createTempFile("server-cache-fixture", ".sqlite", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE channels(channel_id TEXT, display_name TEXT)")
                db.execSQL("CREATE TABLE programmes(channel_id TEXT,start_ms INTEGER,end_ms INTEGER,title TEXT,description TEXT)")
                db.execSQL("INSERT INTO channels VALUES('c1','News')")
                db.execSQL("INSERT INTO programmes VALUES('c1',1,2,?,NULL)", arrayOf(title))
            }
            return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(file.readBytes()) } }.toByteArray()
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun read(snapshot: ServerGuideSnapshot): String = SQLiteDatabase.openDatabase(snapshot.file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT title FROM programmes", null).use { it.moveToFirst(); it.getString(0) }
    }

    @Test fun unchangedManifestReusesVerifiedFileAcrossCacheInstances() = runBlocking {
        fixture { cache, client, state ->
            assertEquals("Old guide", cache.withSnapshot({}) { read(it) })
            val recreated = ServerGuideSnapshotCache(context, client, "https://cache.example.test/epg/full.sqlite.gz")
            assertEquals("Old guide", recreated.withSnapshot({}) { assertTrue(it.fresh); read(it) })
            assertEquals(1, state.downloads)
            assertEquals(2, state.manifests)
        }
    }
    @Test fun offlineRefreshKeepsLastGoodSnapshotWithoutAnotherDownload() = runBlocking {
        fixture { cache, _, state ->
            val original = cache.withSnapshot({}) { it }
            state.offline = true
            val saved = cache.withSnapshot({}) { it }
            assertNotNull(saved)
            assertFalse(saved!!.fresh)
            assertEquals(original!!.hash, saved.hash)
            assertEquals("Old guide", read(saved))
            assertEquals(1, state.downloads)
        }
    }
    @Test fun corruptNewDownloadCannotReplaceLastGoodSnapshot() = runBlocking {
        fixture { cache, _, state ->
            val original = cache.withSnapshot({}) { it }
            state.payload = payload("New guide")
            state.corrupt = true
            val saved = cache.withSnapshot({}) { it }
            assertEquals(original!!.hash, saved!!.hash)
            assertEquals("Old guide", read(saved))
            assertFalse(saved.fresh)
            assertEquals(2, state.downloads)
        }
    }
    @Test fun validNewSnapshotIsPromotedOnlyAfterValidation() = runBlocking {
        fixture { cache, _, state ->
            val original = cache.withSnapshot({}) { it }
            state.payload = payload("New guide")
            val updated = cache.withSnapshot({}) { it }
            assertEquals("New guide", read(updated!!))
            assertTrue(updated.fresh)
            assertEquals("Old guide", read(original!!))
            assertNotEquals(original.hash, updated.hash)
        }
    }
    @Test fun corruptedLatestDiskFileFallsBackToPreviousVerifiedSnapshotOffline() = runBlocking {
        fixture { cache, _, state ->
            cache.withSnapshot({}) { assertEquals("Old guide", read(it)) }
            state.payload = payload("New guide")
            val updated = cache.withSnapshot({}) { it }!!
            updated.file.writeText("Damaged local cache")
            state.offline = true
            val previous = cache.withSnapshot({}) { it }!!
            assertEquals("Old guide", read(previous))
            assertFalse(previous.fresh)
            assertEquals(2, state.downloads)
        }
    }
    private class State(var payload: ByteArray) {
        var downloads = 0; var manifests = 0; var offline = false; var corrupt = false
    }
    private suspend fun fixture(block: suspend (ServerGuideSnapshotCache, OkHttpClient, State) -> Unit) {
        context.getSharedPreferences("server_guide_snapshot", 0).edit().clear().commit()
        val directory = File(context.filesDir, "server_guide_snapshots")
        directory.listFiles().orEmpty().forEach { SQLiteDatabase.deleteDatabase(it) }
        val state = State(payload("Old guide"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val manifest = chain.request().url.encodedPath.endsWith("/manifest")
            val body = if (manifest) {
                state.manifests++
                val hash = MessageDigest.getInstance("SHA-256").digest(state.payload).joinToString("") { "%02x".format(it) }
                "{\"sha256\":\"$hash\",\"bytes\":${state.payload.size}}".toByteArray()
            } else { state.downloads++; if (state.corrupt) byteArrayOf(1, 2, 3) else state.payload }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
                .code(if (state.offline) 503 else 200).body(body.toResponseBody()).build()
        }.build()
        block(ServerGuideSnapshotCache(context, client, "https://cache.example.test/epg/full.sqlite.gz"), client, state)
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("pending-guide-") })
    }
}
