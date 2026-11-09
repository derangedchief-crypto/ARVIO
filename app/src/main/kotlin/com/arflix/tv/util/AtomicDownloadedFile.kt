package com.arflix.tv.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val preparedDownloadDirectories = mutableSetOf<String>()
internal val downloadedFileCommitLock = Any()

/** First use only, before this process creates any pending file in the directory. */
internal fun cleanInterruptedDownloads(directory: File) = synchronized(preparedDownloadDirectories) {
    if (preparedDownloadDirectories.add(directory.absolutePath)) {
        directory.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("download-") && it.name.endsWith(".pending")
        }.forEach { it.delete() }
    }
}

/** A failed/cancelled download must not destroy the last completed copy. */
internal fun replaceDownloadedFile(destination: File, write: (FileOutputStream) -> Unit) {
    val directory = requireNotNull(destination.parentFile)
    check(directory.isDirectory || directory.mkdirs())
    cleanInterruptedDownloads(directory)
    val pending = File.createTempFile("download-", ".pending", directory)
    try {
        FileOutputStream(pending).use { output ->
            write(output)
            output.flush()
            output.fd.sync()
        }
        synchronized(downloadedFileCommitLock) {
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        pending.delete()
    }
}
