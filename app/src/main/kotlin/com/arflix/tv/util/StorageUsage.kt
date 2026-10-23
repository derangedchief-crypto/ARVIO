package com.arflix.tv.util

import java.io.File
import java.nio.file.Files

/** Read-only, fixed category byte counters. Never reads file contents, returns
 * filenames/identifiers or follows links. Counts are approximate during sync. */
internal object StorageUsage {
    val labels = linkedMapOf(
        "catalogues" to "Movie / series catalogues",
        "iptv" to "Saved channel / IPTV metadata",
        "guide_snapshots" to "Retained guide snapshots",
        "guide_xml" to "Retained downloaded guide XML",
        "databases" to "App databases (including guide)",
        "images" to "Artwork cache",
        "http" to "HTTP response cache",
        "updates" to "Downloaded app updates",
        "guide_temp" to "Temporary guide downloads",
        "home" to "Home page cache",
        "other_cache" to "Other temporary cache",
        "compiled" to "Compiled code cache",
        "other_data" to "Other saved app data"
    )
    data class Report(val bytes: Map<String, Long>, val complete: Boolean) {
        val totalBytes: Long get() = bytes.values.sum()
    }

    fun scan(root: File, maxEntries: Int = 100_000): Report {
        require(maxEntries > 0)
        val totals = labels.mapValues { 0L }.toMutableMap()
        var remaining = maxEntries
        var complete = true
        fun walk(file: File, relative: String) {
            if (--remaining < 0) { complete = false; return }
            if (Files.isSymbolicLink(file.toPath())) return
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children == null) { complete = false; return }
                for (child in children) {
                    if (remaining <= 0) { complete = false; break }
                    walk(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                }
            } else if (file.isFile) {
                val bucket = category(relative)
                totals[bucket] = totals.getValue(bucket) + file.length()
            } else if (!file.exists()) {
                complete = false // Concurrent removals/unreadable paths are not zero usage.
            }
        }
        try { walk(root, "") } catch (_: SecurityException) { complete = false }
        return Report(totals.toMap(), complete)
    }

    private fun category(path: String): String = when {
        path.startsWith("files/xtream_vod_disk_cache/") -> "catalogues"
        path.startsWith("files/iptv_cache/") -> "iptv"
        path.startsWith("files/server_guide_snapshots/") -> "guide_snapshots"
        path.startsWith("databases/") -> "databases"
        path.startsWith("cache/image_cache/") -> "images"
        path.startsWith("cache/http_cache/") -> "http"
        path.startsWith("cache/required-updates/") || path.startsWith("cache/updates/") -> "updates"
        path.startsWith("cache/epg_") && path.endsWith(".xmltv") -> "guide_xml"
        path.startsWith("cache/epg_") && (path.endsWith(".xml") || path.endsWith(".xmltv.tmp")) -> "guide_temp"
        path.startsWith("cache/home_categories_cache_") -> "home"
        path.startsWith("cache/") -> "other_cache"
        path.startsWith("code_cache/") -> "compiled"
        else -> "other_data"
    }
}
