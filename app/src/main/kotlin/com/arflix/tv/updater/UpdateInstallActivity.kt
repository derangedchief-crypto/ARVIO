package com.arflix.tv.updater

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Explicit PackageInstaller result destination, retained by the OS across our
 * process/package replacement. Not exported; only the installer gets its token. */
@AndroidEntryPoint
class UpdateInstallActivity : ComponentActivity() {
    @Inject lateinit var updateStatusManager: UpdateStatusManager
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean("handled") ?: false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
    }

    override fun onPostResume() {
        super.onPostResume()
        // Let ActivityThread attach/show the resumed window before handing off.
        // Unknown/malformed callbacks must never unlock application navigation.
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            if (!handled && intent.action == ApkInstallReceiver.actionFor(this)) {
                handled = true
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                ApkInstallResultHandler.handle(this, intent, updateStatusManager)
                if (status in PackageInstaller.STATUS_PENDING_USER_ACTION..PackageInstaller.STATUS_FAILURE_STORAGE &&
                    updateStatusManager.status.value is UpdateStatus.Failure) {
                    UpdateRelaunch.openApp(this)
                }
            }
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("handled", handled)
        super.onSaveInstanceState(outState)
    }
}
