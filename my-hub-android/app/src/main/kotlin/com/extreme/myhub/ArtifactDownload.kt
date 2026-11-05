package com.extreme.myhub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable internal fun ArtifactDownload(route: String, name: String, state: HubState, model: HubModel, sha256: String? = null) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) model.download(route, uri, sha256) }
    Action("Save download", state.busy) { launcher.launch("my-hub-${name.replace(Regex("[^a-zA-Z0-9_-]"), "-")}.${if (sha256 != null) "apk" else "json"}") }
}
