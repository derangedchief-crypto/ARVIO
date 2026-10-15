package com.arflix.tv.updater

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateStatusManagerTest {
    @Test
    fun installSuccessKeepsTheReleaseThatWasInstalled() {
        val update = AppUpdate(
            tag = "v2.0.023",
            title = "Extreme TV 2.0.023",
            notes = "",
            releaseUrl = null,
            assetName = "ExtremeTV-2.0.023.apk",
            assetUrl = "https://example.test/ExtremeTV-2.0.023.apk",
            assetSizeBytes = null,
        )
        val manager = UpdateStatusManager()

        manager.updateStatus(UpdateStatus.UpdateAvailable(update))
        manager.updateStatus(UpdateStatus.Installing(update))
        manager.updateStatus(UpdateStatus.Success)

        val status = manager.status.value
        assertThat(status).isInstanceOf(UpdateStatus.Success::class.java)
        assertThat((status as UpdateStatus.Success).update).isEqualTo(update)
    }
}
