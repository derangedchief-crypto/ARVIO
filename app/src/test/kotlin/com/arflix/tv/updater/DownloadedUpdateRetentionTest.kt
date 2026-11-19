package com.arflix.tv.updater

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedUpdateRetentionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `only completed same-package installed or older APKs are retired`() {
        val names = listOf("old.apk", "installed.apk", "new.apk", "brand.apk", "broken.apk", "download.pending", "notes.txt")
        names.forEach { File(temp.root, it).writeText("fixture") }
        val identities = mapOf("old.apk" to ("app" to 447L), "installed.apk" to ("app" to 450L),
            "new.apk" to ("app" to 451L), "brand.apk" to ("brand" to 440L))
        removeObsoleteUpdateFiles(temp.root, "app", 450) { identities[it.name] }
        assertEquals(names.drop(2).toSet(), temp.root.list()!!.toSet())
    }
}
