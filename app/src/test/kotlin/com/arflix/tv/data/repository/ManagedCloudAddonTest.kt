package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import org.junit.Assert.*
import org.junit.Test

class ManagedCloudAddonTest {
    private fun addon(id: String, url: String) = Addon(id, "Source", "1", "", true,
        type = AddonType.CUSTOM, url = url)

    @Test fun untrackedLegacyMigrationRemovesOnlyKnownOfficialConfigurations() {
        val old = addon("untracked", "https://provider.test/old/manifest.json").copy(isEnabled = false)
        val personal = addon("personal", "https://provider.test/personal/manifest.json")
        val next = addon("new", "https://provider.test/new/manifest.json")
        val (installed, receipt) = replaceManagedCloudAddon(listOf(old, personal), null, next,
            next.url!!, null, setOf(managedManifestFingerprint(old.url!!)))
        assertEquals(setOf("personal", "new"), installed.map { it.id }.toSet())
        assertFalse(installed.single { it.id == "new" }.isEnabled)
        // A cloud record with a different instance ID but the old exact URL is still retired.
        val restored = receipt.protectCloud(listOf(old.copy(id = "other-device"), personal), installed)
        assertEquals(installed, restored)
    }
}
