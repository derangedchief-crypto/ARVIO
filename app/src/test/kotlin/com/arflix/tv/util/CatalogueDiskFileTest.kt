package com.arflix.tv.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogueDiskFileTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `legacy JSON reads without provider or schema changes`() {
        val file = temporary.newFile().apply { writeText("{\"savedAtMs\":12,\"items\":[\"日本語\"]}") }
        assertEquals(file.readText(), CatalogueDiskFile.read(file) { it.readText() })
    }
    @Test fun `new writes are compressed and preserve complete Unicode JSON`() {
        val file = temporary.newFile()
        val json = "{\"items\":[" + List(10_000) { "\"Movie title 日本語\"" }.joinToString(",") + "]}"
        CatalogueDiskFile.write(file) { it.write(json) }
        assertEquals(json, CatalogueDiskFile.read(file) { it.readText() })
        assertTrue(file.length() < json.toByteArray().size / 10)
    }
    @Test fun `failed encoding keeps previous cache and cleans temporary file`() {
        val file = File(temporary.root, "catalogue.json").apply { writeText("old") }
        try { CatalogueDiskFile.write(file) { it.write("partial"); error("test") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals("old", file.readText())
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())
    }
    @Test fun `legacy migration is one time and preserves saved data and timestamp`() {
        val json = "{\"savedAtMs\":55,\"items\":[" + List(80_000) { "1234" }.joinToString(",") + "]}"
        val file = temporary.newFile().apply { writeText(json); setLastModified(123456000L) }
        assertTrue(CatalogueDiskFile.compactLegacy(file))
        assertEquals(123456000L, file.lastModified())
        assertFalse(CatalogueDiskFile.compactLegacy(file))
        assertEquals(json, CatalogueDiskFile.read(file) { it.readText() })
    }
    @Test fun `small legacy caches are not repeatedly rewritten`() {
        val file = temporary.newFile().apply { writeText("{}") }
        assertFalse(CatalogueDiskFile.compactLegacy(file))
        assertEquals("{}", file.readText())
    }
    @Test fun `storage report has disjoint fixed counters and never follows links`() {
        val root = temporary.newFolder()
        fun data(path: String, bytes: Int) { File(root, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)) } }
        data("cache/image_cache/a", 17); data("files/xtream_vod_disk_cache/a", 23)
        data("databases/guide-wal", 7); data("cache/required-updates/a.apk", 11)
        data("shared_prefs/private.xml", 5); data("cache/epg_1.xml", 13)
        data("cache/epg_1.xmltv", 19); data("cache/epg_2.xmltv.tmp", 3)
        val outside = temporary.newFile().apply { writeBytes(ByteArray(100)) }
        Files.createSymbolicLink(File(root, "cache/link").toPath(), outside.toPath())
        val report = StorageUsage.scan(root)
        assertTrue(report.complete); assertEquals(98L, report.totalBytes)
        assertEquals(17L, report.bytes["images"]); assertEquals(16L, report.bytes["guide_temp"]); assertEquals(19L, report.bytes["guide_xml"])
        assertEquals(StorageUsage.labels.keys, report.bytes.keys)
    }
    @Test fun `bounded scan never pretends a truncated measurement is complete`() {
        val root = temporary.newFolder(); repeat(10) { File(root, "a$it").writeText("data") }
        assertFalse(StorageUsage.scan(root, maxEntries = 2).complete)
    }
}
