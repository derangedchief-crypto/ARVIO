package com.arflix.tv.util

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compact replaceable catalogue metadata, NOT preferences, guides or playback data.
 * Existing filenames and JSON schemas remain compatible; magic bytes distinguish
 * old plain JSON from compressed JSON. Readers/writers share the same lock so a
 * migration cannot overwrite a freshly downloaded catalogue. */
internal object CatalogueDiskFile {
    @Synchronized
    fun <T> read(file: File, decode: (Reader) -> T): T {
        file.inputStream().buffered().use { input ->
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val stream = if (gzip) GZIPInputStream(input) else input
            return InputStreamReader(stream, StandardCharsets.UTF_8).use(decode)
        }
    }

    @Synchronized
    fun write(file: File, encode: (Writer) -> Unit) {
        replace(file) { output ->
            GZIPOutputStream(KeepOpen(output)).writer(StandardCharsets.UTF_8).buffered().use(encode)
        }
    }

    /** One-time compaction after a successful legacy read; no provider request,
     * TTL change or loss of a stale/offline fallback. Failed replacement keeps old data. */
    @Synchronized
    fun compactLegacy(file: File): Boolean {
        if (file.length() < 256 * 1024) return false
        file.inputStream().use { if (it.read() == 0x1f && it.read() == 0x8b) return false }
        val modified = file.lastModified()
        replace(file) { output ->
            GZIPOutputStream(KeepOpen(output)).use { gzip ->
                file.inputStream().use { it.copyTo(gzip) }
            }
        }
        file.setLastModified(modified)
        return true
    }

    private fun replace(file: File, encode: (FileOutputStream) -> Unit) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Catalogue cache unavailable" }
        val pending = File.createTempFile("catalogue-", ".pending", parent)
        try {
            FileOutputStream(pending).use { output ->
                encode(output)
                output.flush()
                output.fd.sync()
            }
            // Same-filesystem atomic rename; do not copy over the known-good file.
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    private class KeepOpen(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush() // Caller fsyncs and owns the file descriptor.
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
    }
}
