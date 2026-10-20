package com.arflix.tv.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local guide index used by the Live TV page.
 *
 * The JSON IPTV snapshot is intentionally capped so it cannot grow without
 * bound on 50k-channel lists. This SQLite index stores parsed program rows
 * separately, allowing the UI to query only the visible channels instantly.
 */
internal class IptvEpgIndex(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val snapshotMutex = Mutex()
    data class SourceState(
        val generation: Long,
        val status: String,
        val updatedAtMs: Long,
        val refreshStartedAtMs: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE epg_programs (
                source_key TEXT NOT NULL,
                generation INTEGER NOT NULL,
                channel_id TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                PRIMARY KEY(source_key, generation, channel_id, start_ms, end_ms, title)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_epg_programs_window ON epg_programs(source_key, generation, channel_id, start_ms, end_ms)"
        )
        db.execSQL(
            """
            CREATE TABLE epg_sources (
                source_key TEXT PRIMARY KEY NOT NULL,
                updated_ms INTEGER NOT NULL,
                generation INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ready',
                refresh_started_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            // v4 adds a generation to every programme key. Recreate the
            // compact index rather than attempting to retrofit a SQLite
            // primary key; XMLTV can safely repopulate it in the background.
            db.execSQL("DROP TABLE IF EXISTS epg_programs")
            db.execSQL("DROP TABLE IF EXISTS epg_sources")
            onCreate(db)
        }
    }

    fun beginRefresh(sourceKey: String, startedAtMs: Long = System.currentTimeMillis()) {
        if (sourceKey.isBlank()) return
        writableDatabase.runInTransaction {
            execSQL(
                "INSERT OR IGNORE INTO epg_sources(source_key, updated_ms, generation, status, refresh_started_ms) VALUES (?, ?, 0, 'refreshing', ?)",
                arrayOf<Any?>(sourceKey, 0L, startedAtMs),
            )
            execSQL(
                "UPDATE epg_sources SET status = 'refreshing', refresh_started_ms = ? WHERE source_key = ?",
                arrayOf<Any?>(startedAtMs, sourceKey),
            )
        }
    }

    fun markRefreshFailed(sourceKey: String) {
        if (sourceKey.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE epg_sources SET status = 'stale' WHERE source_key = ?",
            arrayOf(sourceKey),
        )
    }

    fun markRefreshReady(sourceKey: String, updatedAtMs: Long = System.currentTimeMillis()) {
        if (sourceKey.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE epg_sources SET updated_ms = ?, status = 'ready', refresh_started_ms = 0 WHERE source_key = ?",
            arrayOf<Any?>(updatedAtMs, sourceKey),
        )
    }

    fun sourceState(sourceKey: String): SourceState? {
        if (sourceKey.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT generation, status, updated_ms, refresh_started_ms FROM epg_sources WHERE source_key = ?",
            arrayOf(sourceKey),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SourceState(
                generation = cursor.getLong(0),
                status = cursor.getString(1).orEmpty(),
                updatedAtMs = cursor.getLong(2),
                refreshStartedAtMs = cursor.getLong(3),
            )
        }
    }

    fun replaceAll(sourceKey: String, nowNext: Map<String, IptvNowNext>, updatedAtMs: Long) {
        if (sourceKey.isBlank() || nowNext.isEmpty()) return

        writableDatabase.runInTransaction {
            ensureSource(sourceKey, updatedAtMs)
            val nextGeneration = activeGeneration(sourceKey) + 1L
            delete(
                "epg_programs",
                "source_key = ? AND generation = ?",
                arrayOf(sourceKey, nextGeneration.toString()),
            )
            insertNowNextRows(sourceKey, nextGeneration, nowNext)
            completeSourceRefresh(sourceKey, nextGeneration, updatedAtMs)
        }
    }

    fun replaceChannels(sourceKey: String, nowNext: Map<String, IptvNowNext>, updatedAtMs: Long) {
        if (sourceKey.isBlank() || nowNext.isEmpty()) return

        writableDatabase.runInTransaction {
            ensureSource(sourceKey, updatedAtMs)
            val generation = ensureActiveGeneration(sourceKey)
            nowNext.keys
                .asSequence()
                .filter { it.isNotBlank() }
                .chunked(MAX_SQL_ARGS - 1)
                .forEach { channelIds ->
                    val placeholders = channelIds.joinToString(",") { "?" }
                    val args = arrayOf(sourceKey, generation.toString()) + channelIds.toTypedArray()
                    delete(
                        "epg_programs",
                        "source_key = ? AND generation = ? AND channel_id IN ($placeholders)",
                        args,
                    )
                }
            insertNowNextRows(sourceKey, generation, nowNext)
            touchSourceForPartialUpdate(sourceKey, updatedAtMs)
        }
    }

    /**
     * Short now/next responses and incremental XMLTV batches are NOT complete
     * channel schedules. Keep the programmes they did not return, replacing
     * only listings with matching start times. A refresh must not erase a
     * previously loaded category or the rest of a channel's day.
     */
    fun mergeChannels(sourceKey: String, nowNext: Map<String, IptvNowNext>, updatedAtMs: Long, pruneHistory: Boolean = true) {
        if (sourceKey.isBlank() || nowNext.isEmpty()) return
        writableDatabase.runInTransaction {
            ensureSource(sourceKey, updatedAtMs)
            val generation = ensureActiveGeneration(sourceKey)
            insertNowNextRows(sourceKey, generation, nowNext, replaceMatchingStarts = true)
            // Same bounded history as ordinary index reads; expired broadcasts
            // are not retained indefinitely as successive feeds are merged.
            if (pruneHistory) delete("epg_programs", "source_key = ? AND end_ms < ?",
                arrayOf(sourceKey, (updatedAtMs - DEFAULT_PAST_WINDOW_MS).toString()))
            touchSourceForPartialUpdate(sourceKey, updatedAtMs)
        }
    }

    fun pruneExpiredHistory(sourceKey: String, updatedAtMs: Long) {
        if (sourceKey.isBlank()) return
        writableDatabase.delete("epg_programs", "source_key = ? AND end_ms < ?",
            arrayOf(sourceKey, (updatedAtMs - DEFAULT_PAST_WINDOW_MS).toString()))
    }

    fun hasCurrentPrograms(sourceKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val generation = sourceState(sourceKey)?.generation ?: return false
        return readableDatabase.rawQuery("SELECT 1 FROM epg_programs WHERE source_key=? AND generation=? AND end_ms>? AND start_ms<? LIMIT 1",
            arrayOf(sourceKey, generation.toString(), nowMs.toString(), (nowMs + 6 * 60 * 60_000L).toString())).use { it.moveToFirst() }
    }

    fun currentChannelIds(sourceKey: String, nowMs: Long = System.currentTimeMillis()): Set<String> {
        val generation = sourceState(sourceKey)?.generation ?: return emptySet()
        return readableDatabase.rawQuery(
            "SELECT DISTINCT channel_id FROM epg_programs WHERE source_key=? AND generation=? AND end_ms>? AND start_ms<?",
            arrayOf(sourceKey, generation.toString(), nowMs.toString(), (nowMs + 6 * 60 * 60_000L).toString())
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Native SQLite copy: no per-programme Kotlin objects/queries or whole-guide map. */
    suspend fun mergeServerSnapshot(sourceKey: String, file: File, mappings: List<Pair<String, String>>,
        startBound: Long, endBound: Long, updatedAtMs: Long,
        onBatch: suspend (Int, Int) -> Unit = { _, _ -> },
    ) = snapshotMutex.withLock {
        if (sourceKey.isBlank() || mappings.isEmpty()) return@withLock
        val db = writableDatabase
        db.execSQL("ATTACH DATABASE ? AS shared_guide", arrayOf(file.absolutePath))
        try {
            db.execSQL("CREATE TEMP TABLE server_guide_map(feed_id TEXT NOT NULL, channel_id TEXT NOT NULL, PRIMARY KEY(feed_id,channel_id))")
            db.execSQL("CREATE INDEX server_guide_map_channel ON server_guide_map(channel_id,feed_id)")
            db.runInTransaction {
                ensureSource(sourceKey, updatedAtMs)
                ensureActiveGeneration(sourceKey)
                compileStatement("INSERT OR IGNORE INTO server_guide_map VALUES (?,?)").use { statement ->
                    mappings.forEach { (feed, channel) ->
                        statement.bindString(1, feed)
                        statement.bindString(2, channel)
                        statement.executeInsert()
                    }
                }
            }
            val generation = db.activeGeneration(sourceKey)
            val feeds = mappings.map { it.first }.distinct()
            var visited = 0
            for (chunk in feeds.chunked(64)) {
                currentCoroutineContext().ensureActive()
                val placeholders = chunk.joinToString(",") { "?" }
                val args = buildList<Any> {
                    add(sourceKey); add(generation); addAll(chunk); add(startBound); add(endBound)
                }.toTypedArray()
                db.runInTransaction {
                    // Correct matching timestamps atomically. Unreturned channels,
                    // other broadcasts and previous usable listings stay intact.
                    execSQL("""DELETE FROM epg_programs WHERE source_key=? AND generation=?
                        AND channel_id IN (SELECT channel_id FROM server_guide_map WHERE feed_id IN ($placeholders))
                        AND EXISTS (SELECT 1 FROM server_guide_map m JOIN shared_guide.programmes p ON p.channel_id=m.feed_id
                        WHERE m.channel_id=epg_programs.channel_id AND p.start_ms=epg_programs.start_ms
                        AND p.end_ms>? AND p.start_ms<? AND p.end_ms>p.start_ms AND trim(p.title)!=''
                        AND (p.end_ms!=epg_programs.end_ms OR trim(p.title)!=epg_programs.title))""", args)
                    execSQL("""INSERT OR REPLACE INTO epg_programs
                        (source_key,generation,channel_id,start_ms,end_ms,title,description)
                        SELECT ?,?,m.channel_id,p.start_ms,p.end_ms,trim(p.title),substr(p.description,1,$MAX_DESCRIPTION_CHARS)
                        FROM shared_guide.programmes p JOIN server_guide_map m ON p.channel_id=m.feed_id
                        WHERE m.feed_id IN ($placeholders) AND p.end_ms>? AND p.start_ms<?
                        AND p.end_ms>p.start_ms AND trim(p.title)!=''""", args)
                }
                visited += chunk.size
                onBatch(visited, feeds.size)
            }
            pruneExpiredHistory(sourceKey, updatedAtMs)
        } finally {
            try { db.execSQL("DROP TABLE IF EXISTS temp.server_guide_map") }
            finally { db.execSQL("DETACH DATABASE shared_guide") }
        }
    }

    fun loadNowNext(
        sourceKey: String,
        channelIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        pastWindowMs: Long = DEFAULT_PAST_WINDOW_MS,
        futureWindowMs: Long = DEFAULT_FUTURE_WINDOW_MS
    ): Map<String, IptvNowNext> {
        if (sourceKey.isBlank() || channelIds.isEmpty()) return emptyMap()
        val startBound = nowMs - pastWindowMs
        val endBound = nowMs + futureWindowMs
        val grouped = LinkedHashMap<String, MutableList<IptvProgram>>()

        val generation = sourceState(sourceKey)?.generation ?: return emptyMap()
        if (generation <= 0L) return emptyMap()
        readableDatabase.useQueryChunks(
            sourceKey = sourceKey,
            generation = generation,
            channelIds = channelIds,
            startBound = startBound,
            endBound = endBound
        ) { channelId, program ->
            grouped.getOrPut(channelId) { mutableListOf() }.add(program)
        }

        if (grouped.isEmpty()) return emptyMap()
        return buildMap {
            grouped.forEach { (channelId, programs) ->
                buildNowNext(programs, nowMs)?.let { put(channelId, it) }
            }
        }
    }

    /**
     * Load the full list of programmes that overlap [startMs, endMs) for the given
     * channels, sorted by start time and de-duplicated.
     *
     * Unlike [loadNowNext] this does NOT compact the result into a now/next slice —
     * the Live TV guide grid needs every programme in the visible window (e.g. the
     * full past-48h / future-48h span) so it can lay out cells across the timeline.
     */
    fun loadWindow(
        sourceKey: String,
        channelIds: Set<String>,
        startMs: Long,
        endMs: Long
    ): Map<String, List<IptvProgram>> {
        if (sourceKey.isBlank() || channelIds.isEmpty() || endMs <= startMs) return emptyMap()
        val grouped = LinkedHashMap<String, MutableList<IptvProgram>>()

        val generation = sourceState(sourceKey)?.generation ?: return emptyMap()
        if (generation <= 0L) return emptyMap()
        readableDatabase.useQueryChunks(
            sourceKey = sourceKey,
            generation = generation,
            channelIds = channelIds,
            startBound = startMs,
            endBound = endMs
        ) { channelId, program ->
            grouped.getOrPut(channelId) { mutableListOf() }.add(program)
        }

        if (grouped.isEmpty()) return emptyMap()
        return buildMap {
            grouped.forEach { (channelId, programs) ->
                val sorted = programs
                    .asSequence()
                    .filter { it.endUtcMillis > it.startUtcMillis }
                    .distinctBy { "${it.startUtcMillis}|${it.endUtcMillis}|${it.title}" }
                    .sortedBy { it.startUtcMillis }
                    .toList()
                if (sorted.isNotEmpty()) put(channelId, sorted)
            }
        }
    }

    fun countChannelsWithPrograms(sourceKey: String): Int {
        if (sourceKey.isBlank()) return 0
        return readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT channel_id) FROM epg_programs WHERE source_key = ?",
            arrayOf(sourceKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun countPrograms(sourceKey: String): Int {
        if (sourceKey.isBlank()) return 0
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM epg_programs WHERE source_key = ?",
            arrayOf(sourceKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun deleteSource(sourceKey: String) {
        if (sourceKey.isBlank()) return
        writableDatabase.runInTransaction {
            delete("epg_programs", "source_key = ?", arrayOf(sourceKey))
            delete("epg_sources", "source_key = ?", arrayOf(sourceKey))
        }
    }

    private fun SQLiteDatabase.useQueryChunks(
        sourceKey: String,
        generation: Long,
        channelIds: Set<String>,
        startBound: Long,
        endBound: Long,
        onProgram: (String, IptvProgram) -> Unit
    ) {
        channelIds
            .asSequence()
            .filter { it.isNotBlank() }
            .chunked(MAX_SQL_ARGS - 4)
            .forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = """
                    SELECT channel_id, start_ms, end_ms, title, description
                    FROM epg_programs
                    WHERE source_key = ?
                      AND generation = ?
                      AND channel_id IN ($placeholders)
                      AND end_ms > ?
                      AND start_ms < ?
                    ORDER BY channel_id, start_ms
                """.trimIndent()
                val args = buildList {
                    add(sourceKey)
                    add(generation.toString())
                    addAll(chunk)
                    add(startBound.toString())
                    add(endBound.toString())
                }.toTypedArray()

                rawQuery(sql, args).use { cursor ->
                    val channelCol = cursor.getColumnIndexOrThrow("channel_id")
                    val startCol = cursor.getColumnIndexOrThrow("start_ms")
                    val endCol = cursor.getColumnIndexOrThrow("end_ms")
                    val titleCol = cursor.getColumnIndexOrThrow("title")
                    val descCol = cursor.getColumnIndexOrThrow("description")
                    while (cursor.moveToNext()) {
                        val channelId = cursor.getString(channelCol).orEmpty()
                        val startMs = cursor.getLong(startCol)
                        val endMs = cursor.getLong(endCol)
                        val title = cursor.getString(titleCol).orEmpty()
                        if (channelId.isBlank() || title.isBlank() || endMs <= startMs) continue
                        val description = if (cursor.isNull(descCol)) null else cursor.getString(descCol)
                        onProgram(
                            channelId,
                            IptvProgram(
                                title = title,
                                description = description?.takeIf { it.isNotBlank() },
                                startUtcMillis = startMs,
                                endUtcMillis = endMs
                            )
                        )
                    }
                }
            }
    }

    private class ProgramDedupKey(val start: Long, val end: Long, val title: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProgramDedupKey) return false
            return start == other.start && end == other.end && title == other.title
        }
        override fun hashCode(): Int {
            var result = start.hashCode()
            result = 31 * result + end.hashCode()
            result = 31 * result + title.hashCode()
            return result
        }
    }

    private fun SQLiteDatabase.insertNowNextRows(
        sourceKey: String,
        generation: Long,
        nowNext: Map<String, IptvNowNext>,
        replaceMatchingStarts: Boolean = false,
    ) {
        val statement = compileStatement(
            """
            INSERT OR REPLACE INTO epg_programs
            (source_key, generation, channel_id, start_ms, end_ms, title, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        val removeSameStart = if (replaceMatchingStarts) compileStatement(
            "DELETE FROM epg_programs WHERE source_key = ? AND generation = ? AND channel_id = ? AND start_ms = ?"
        ) else null
        try {
            val seenPrograms = HashSet<ProgramDedupKey>(128)
            nowNext.forEach { (channelId, item) ->
                val normalizedId = channelId.trim()
                if (normalizedId.isBlank()) return@forEach
                seenPrograms.clear()

                fun insertProgram(program: IptvProgram) {
                    if (program.title.isBlank() || program.endUtcMillis <= program.startUtcMillis) return
                    val titleTrimmed = program.title.trim()
                    val key = ProgramDedupKey(program.startUtcMillis, program.endUtcMillis, titleTrimmed)
                    if (!seenPrograms.add(key)) return

                    val description = program.description?.trim()?.take(MAX_DESCRIPTION_CHARS)
                    removeSameStart?.apply {
                        clearBindings()
                        bindString(1, sourceKey)
                        bindLong(2, generation)
                        bindString(3, normalizedId)
                        bindLong(4, program.startUtcMillis)
                        executeUpdateDelete()
                    }
                    statement.clearBindings()
                    statement.bindString(1, sourceKey)
                    statement.bindLong(2, generation)
                    statement.bindString(3, normalizedId)
                    statement.bindLong(4, program.startUtcMillis)
                    statement.bindLong(5, program.endUtcMillis)
                    statement.bindString(6, titleTrimmed)
                    if (description.isNullOrBlank()) {
                        statement.bindNull(7)
                    } else {
                        statement.bindString(7, description)
                    }
                    statement.executeInsert()
                }

                item.now?.let(::insertProgram)
                item.next?.let(::insertProgram)
                item.later?.let(::insertProgram)
                item.upcoming.forEach(::insertProgram)
                item.recent.forEach(::insertProgram)
            }
        } finally {
            removeSameStart?.close()
            statement.close()
        }
    }

    private fun SQLiteDatabase.touchSourceForPartialUpdate(sourceKey: String, updatedAtMs: Long) {
        // Do not advance generation or mark a refreshing guide as ready: a
        // partial parser batch is usable immediately but is not a completed
        // replacement for the previous source generation.
        execSQL(
            "UPDATE epg_sources SET updated_ms = ? WHERE source_key = ? AND status != 'refreshing'",
            arrayOf<Any?>(updatedAtMs, sourceKey),
        )
    }

    private fun SQLiteDatabase.ensureSource(sourceKey: String, updatedAtMs: Long) {
        execSQL(
            "INSERT OR IGNORE INTO epg_sources(source_key, updated_ms, generation, status, refresh_started_ms) VALUES (?, ?, 0, 'ready', 0)",
            arrayOf<Any?>(sourceKey, updatedAtMs),
        )
    }

    private fun SQLiteDatabase.activeGeneration(sourceKey: String): Long = rawQuery(
        "SELECT generation FROM epg_sources WHERE source_key = ?",
        arrayOf(sourceKey),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private fun SQLiteDatabase.ensureActiveGeneration(sourceKey: String): Long {
        val current = activeGeneration(sourceKey)
        if (current > 0L) return current
        execSQL(
            "UPDATE epg_sources SET generation = 1 WHERE source_key = ?",
            arrayOf(sourceKey),
        )
        return 1L
    }

    private fun SQLiteDatabase.completeSourceRefresh(
        sourceKey: String,
        generation: Long,
        updatedAtMs: Long,
    ) {
        execSQL(
            "UPDATE epg_sources SET updated_ms = ?, generation = ?, status = 'ready', refresh_started_ms = 0 WHERE source_key = ?",
            arrayOf<Any?>(updatedAtMs, generation, sourceKey),
        )
        delete("epg_programs", "source_key = ? AND generation != ?", arrayOf(sourceKey, generation.toString()))
    }

    private fun buildNowNext(programs: List<IptvProgram>, nowMs: Long): IptvNowNext? {
        if (programs.isEmpty()) return null
        val sorted = programs
            .asSequence()
            .filter { it.endUtcMillis > it.startUtcMillis }
            .distinctBy { "${it.startUtcMillis}|${it.endUtcMillis}|${it.title}" }
            .sortedBy { it.startUtcMillis }
            .toList()
        if (sorted.isEmpty()) return null

        val now = sorted.lastOrNull { it.isLive(nowMs) }
        val future = sorted
            .asSequence()
            .filter { it.startUtcMillis > nowMs }
            .take(MAX_UPCOMING_PROGRAMS)
            .toList()
        val recent = sorted
            .filter { it.endUtcMillis <= nowMs }
            .takeLast(MAX_RECENT_PROGRAMS)

        val result = IptvNowNext(
            now = now,
            next = future.getOrNull(0),
            later = future.getOrNull(1),
            upcoming = future,
            recent = recent
        )
        return if (
            result.now != null ||
            result.next != null ||
            result.later != null ||
            result.upcoming.isNotEmpty() ||
            result.recent.isNotEmpty()
        ) {
            result
        } else {
            null
        }
    }

    private inline fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }


    private companion object {
        const val DATABASE_NAME = "arvio_iptv_epg_index.db"
        // v2 dropped the guide table on upgrade. A previous build's full-guide backfill
        // bloated it with up to 336 programmes/channel; loading 360 such channels into
        // memory churned the heap and crashed the Live TV page. Recreating it clears
        // that, and the reduced caps below keep per-channel memory bounded.
        const val DATABASE_VERSION = 4
        const val MAX_SQL_ARGS = 900
        const val MAX_DESCRIPTION_CHARS = 200
        // ±48h of guide needs only ~24-48 programmes each way. Keeping 96+240 held far
        // more in memory than the grid ever shows.
        const val MAX_UPCOMING_PROGRAMS = 48
        const val MAX_RECENT_PROGRAMS = 48
        const val DEFAULT_PAST_WINDOW_MS = 48L * 60L * 60_000L
        const val DEFAULT_FUTURE_WINDOW_MS = 48L * 60L * 60_000L
    }
}
