package com.arflix.tv.management

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],manifest=Config.NONE,application=Application::class)
class ManagementVaultRecoveryTest {
    private val scope = "a".repeat(64)
    private fun directory(): File = File(ApplicationProvider.getApplicationContext<Application>().noBackupFilesDir,"device-management-v1").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    @Test fun legacyAtomicBackupMustNotBeTreatedAsNewInstallation() {
        val dir=directory()
        val backup=File(dir,"$scope.enc.bak").apply { writeBytes(byteArrayOf(0)) }
        // An invalid encrypted record must fail closed, not return null and
        // let the agent overwrite the old installation with a fresh identity.
        val vault=ManagementVault(ApplicationProvider.getApplicationContext())
        assertThrows(Exception::class.java) { vault.load(scope) }
        assertTrue(File(dir,"$scope.enc").exists() || backup.exists())
    }

    @Test fun unfinishedFirstWriteMustNotBeTreatedAsNewInstallation() {
        val dir=directory()
        File(dir,"$scope.enc.new").writeBytes(byteArrayOf(1,2,3))
        val vault=ManagementVault(ApplicationProvider.getApplicationContext())
        assertThrows(Exception::class.java) { vault.load(scope) }
        assertTrue(File(dir,"$scope.enc.new").exists())
    }

    @Test fun genuinelyAbsentStateRemainsEligibleForFirstEnrollment() {
        directory()
        val vault=ManagementVault(ApplicationProvider.getApplicationContext())
        assertNull(vault.load(scope))
    }

    @Test fun corruptExistingStateIsNeverAFirstEnrollment() {
        val dir=directory()
        val base=File(dir,"$scope.enc").apply { writeBytes(byteArrayOf(0)) }
        val vault=ManagementVault(ApplicationProvider.getApplicationContext())
        assertThrows(Exception::class.java) { vault.load(scope) }
        assertArrayEquals(byteArrayOf(0),base.readBytes())
    }
}
