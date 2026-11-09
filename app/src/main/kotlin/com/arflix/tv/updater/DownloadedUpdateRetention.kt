package com.arflix.tv.updater

import android.content.Context
import android.os.Build
import java.io.File

/** Run on IO after startup. Only completed APKs already superseded by the
 * installed package are obsolete. Pending/newer installers remain available. */
internal fun removeInstalledUpdateDownloads(context: Context) {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val installed = pm.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
    for (name in listOf("updates", "required-updates")) {
        val directory = File(context.cacheDir, name)
        com.arflix.tv.util.cleanInterruptedDownloads(directory)
        removeObsoleteUpdateFiles(directory, context.packageName, version) { file ->
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.absolutePath, 0)
            archive?.let {
                @Suppress("DEPRECATION")
                it.packageName to if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            }
        }
    }
}

internal fun removeObsoleteUpdateFiles(directory: File, packageName: String, installedVersion: Long,
    identity: (File) -> Pair<String, Long>?) {
    directory.listFiles().orEmpty().filter { it.isFile && it.extension.equals("apk", true) }.forEach { file ->
        runCatching {
            synchronized(com.arflix.tv.util.downloadedFileCommitLock) {
            val archive = identity(file)
            if (archive != null && archive.first == packageName && archive.second <= installedVersion) file.delete()
            }
        }
    }
}
