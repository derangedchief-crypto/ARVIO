package com.arflix.tv.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal data class ServerGuideSnapshot(val file: File, val hash: String, val fresh: Boolean)

/** Immutable, verified last-good server files survive refreshes and process restarts. */
internal class ServerGuideSnapshotCache(context: Context, client: OkHttpClient, private val url: String) {
    private val mutex = Mutex()
    private val directory = File(context.filesDir, "server_guide_snapshots")
    private val prefs = context.getSharedPreferences("server_guide_snapshot", Context.MODE_PRIVATE)
    private val http = client.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    private val hashPattern = Regex("[a-fA-F0-9]{64}")
    private var cleanedPendingFiles = false

    suspend fun <T> withSnapshot(progress: (IptvLoadProgress) -> Unit,
        accepted: (T) -> Boolean = { true }, use: suspend (ServerGuideSnapshot) -> T): T? =
        withContext(Dispatchers.IO) { mutex.withLock {
            directory.mkdirs()
            if (!cleanedPendingFiles) {
                // Only this singleton writes these temporary files. Any pending
                // files on its first use belong to an interrupted old process.
                directory.listFiles().orEmpty().filter { it.name.startsWith("pending-guide-") }
                    .forEach { SQLiteDatabase.deleteDatabase(it) }
                cleanedPendingFiles = true
            }
            val savedHash = prefs.getString("hash", null)?.takeIf { hashPattern.matches(it) }
            val savedFile = savedHash?.let { File(directory, "server-$it.sqlite") }
            val candidates = (listOfNotNull(savedFile) + directory.listFiles().orEmpty()
                .filter { it.name.startsWith("server-") && it.extension == "sqlite" }
                .sortedByDescending { it.lastModified() }).distinct()
            val saved = candidates.firstNotNullOfOrNull { file ->
                val hash = file.name.removePrefix("server-").removeSuffix(".sqlite")
                if (hashPattern.matches(hash) && validDatabase(file, integrity = true)) ServerGuideSnapshot(file, hash, false) else null
            }
            progress(IptvLoadProgress("Checking server guide cache", 0))
            val manifest = try {
                http.newCall(Request.Builder().url(url.substringBeforeLast('/') + "/manifest").build()).execute().use { response ->
                    val body = response.body ?: return@use null
                    if (!response.isSuccessful) null else JsonParser.parseString(body.string()).asJsonObject.let { json ->
                        val hash = json.get("sha256")?.asString?.takeIf { hashPattern.matches(it) }?.lowercase()
                        if (hash == null) null else hash to (json.get("bytes")?.asLong ?: 0L)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (saved != null && manifest?.first == saved.hash) {
                progress(IptvLoadProgress("Server guide unchanged — using saved cache", 55))
                return@withLock useAndRetire(saved.copy(fresh = true), accepted, use)
            }
            if (saved != null && manifest == null) {
                progress(IptvLoadProgress("Server unavailable — keeping saved guide", 55))
                return@withLock useAndRetire(saved, accepted, use)
            }
            val compressed = File.createTempFile("pending-guide-", ".gz", directory)
            val staged = File.createTempFile("pending-guide-", ".sqlite", directory)
            var snapshot: ServerGuideSnapshot? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { response ->
                    check(response.isSuccessful) { "Server guide HTTP ${response.code}" }
                    val body = requireNotNull(response.body) { "Server guide response was empty" }
                    val total = body.contentLength().takeIf { it > 0 } ?: manifest?.second ?: 0L
                    var bytes = 0L
                    var lastReport = 0L
                    var lastReportAt = 0L
                    body.byteStream().use { input -> compressed.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            bytes += count
                            if ((bytes - lastReport >= 256 * 1024 && System.currentTimeMillis() - lastReportAt >= 250) || bytes == total) {
                                lastReport = bytes
                                lastReportAt = System.currentTimeMillis()
                                val percent = if (total > 0) (bytes * 45 / total).toInt().coerceAtMost(45) else 1
                                progress(IptvLoadProgress("Downloading server guide (${bytes / 1024} KB" +
                                    (if (total > 0) "/${total / 1024} KB)" else ")"), percent))
                            }
                        }
                    } }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(manifest == null || manifest.first == hash) { "Server guide changed or download was incomplete" }
                progress(IptvLoadProgress("Preparing downloaded guide cache", 48))
                GZIPInputStream(compressed.inputStream()).use { input -> staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                check(validDatabase(staged, integrity = true)) { "Invalid server guide database" }
                val target = File(directory, "server-$hash.sqlite")
                check(staged.renameTo(target)) { "Could not retain downloaded guide" }
                snapshot = ServerGuideSnapshot(target, hash, true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                snapshot = saved
                if (saved != null) progress(IptvLoadProgress("Update unavailable — using saved guide cache", 55))
            } finally {
                compressed.delete()
                SQLiteDatabase.deleteDatabase(staged)
            }
            val selected = snapshot ?: return@withLock null
            useAndRetire(selected, accepted, use)
        } }

    private suspend fun <T> useAndRetire(snapshot: ServerGuideSnapshot, accepted: (T) -> Boolean,
        use: suspend (ServerGuideSnapshot) -> T): T {
        val result = use(snapshot)
        // Only retire old files AFTER the caller successfully installs a usable
        // replacement. Exceptions, cancellation and empty imports keep fallback.
        if (accepted(result)) {
            if (prefs.edit().putString("hash", snapshot.hash).commit()) {
                directory.listFiles().orEmpty().filter {
                    it != snapshot.file && it.name.matches(Regex("server-[a-fA-F0-9]{64}\\.sqlite"))
                }.forEach { SQLiteDatabase.deleteDatabase(it) }
            }
        }
        return result
    }

    private fun validDatabase(file: File, integrity: Boolean = false): Boolean = try {
        if (!file.isFile || file.length() == 0L) false else SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT channel_id, display_name FROM channels LIMIT 0", null).close()
            db.rawQuery("SELECT channel_id, start_ms, end_ms, title, description FROM programmes LIMIT 0", null).close()
            !integrity || db.rawQuery("PRAGMA quick_check(1)", null).use { it.moveToFirst() && it.getString(0) == "ok" }
        }
    } catch (_: Exception) { false }
}
