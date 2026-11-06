package com.extreme.myhub

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun HubUpdatePrompt(state: HubUpdateState, updates: HubUpdates) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) updates.install(context)
    }
    if (!state.visible) return
    AlertDialog(onDismissRequest = updates::dismiss,
        title = { Text(if (state.release != null) "My Hub update available" else "My Hub updates") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Installed: ${BuildConfig.VERSION_NAME.removeSuffix("-dev")}")
            state.release?.let { Text("New version: ${it.version}") }
            if (state.downloading) { LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("Downloading ${state.progress}%") }
            else if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking…") }
            state.message?.let { Text(it) }
            if (state.ready) Text("Android will ask you to confirm installation. If prompted, allow My Hub to install apps, then return here.")
        } },
        confirmButton = {
            if (state.release != null) TextButton(enabled = !state.busy, onClick = {
                if (!state.ready) updates.download()
                else if (context.packageManager.canRequestPackageInstalls()) updates.install(context)
                else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }) { Text(if (state.ready) "Install update" else "Download update") }
            else if (!state.busy) TextButton(onClick = { updates.check(true) }) { Text("Check again") }
        },
        dismissButton = { TextButton(onClick = updates::dismiss, enabled = !state.busy) { Text(if (state.release != null) "Later" else "Close") } })
}
