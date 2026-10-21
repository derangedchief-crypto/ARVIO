package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.updater.RequiredUpdateState
import com.arflix.tv.updater.UpdateStatus

/** Remove navigation/players entirely: neither Back nor an ignored update can bypass this gate. */
@Composable
fun RequiredUpdateGate(
    state: RequiredUpdateState,
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onPermission: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!state.blocksApp) { content(); return }
    BackHandler { /* Updating is required before navigation can resume. */ }
    val focus = remember { FocusRequester() }
    val update = state.update
    val downloading = status is UpdateStatus.Downloading && status.update.tag == update?.tag
    val installing = status is UpdateStatus.Installing
    Box(Modifier.fillMaxSize().background(Color(0xFF090D15)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (update == null) "Checking for updates" else "Update required",
                color = Color.White, fontSize = 28.sp, textAlign = TextAlign.Center)
            if (update == null) {
                CircularProgressIndicator()
            } else {
                Text("Version ${update.tag.removePrefix("v")} is available. You need to update to continue using Extreme Hub.",
                    color = Color(0xFFD5DBE5), fontSize = 18.sp, textAlign = TextAlign.Center)
                when {
                    state.permissionRequired -> Text("Allow Extreme Hub to install updates in Android settings, then return here.", color = Color.White)
                    downloading -> {
                        val progress = (status as UpdateStatus.Downloading).progress
                        LinearProgressIndicator(progress = progress ?: 0f, modifier = Modifier.fillMaxWidth())
                        Text(progress?.let { "Downloading update: ${(it * 100).toInt()}%" } ?: "Downloading update…", color = Color.White)
                    }
                    installing -> Text("Confirm the installation in Android. If it was cancelled, retry below.", color = Color.White)
                    status is UpdateStatus.Failure && status.update?.tag == update.tag -> Text(status.message, color = Color(0xFFFFB4AB))
                }
                if (!downloading) {
                    val install = status is UpdateStatus.ReadyToInstall || installing || status is UpdateStatus.Failure
                    Button(onClick = when {
                        state.permissionRequired -> onPermission
                        install -> onInstall
                        else -> onDownload
                    }, modifier = Modifier.focusRequester(focus)) {
                        Text(when {
                            state.permissionRequired -> "Allow installation"
                            installing -> "Retry installation"
                            status is UpdateStatus.Failure -> "Retry update"
                            install -> "Install update"
                            else -> "Update now"
                        })
                    }
                    LaunchedEffect(update.tag, state.permissionRequired, status::class) { focus.requestFocus() }
                }
            }
        }
    }
}
