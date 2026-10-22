package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.management.DeviceManagementState
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.delay

val LocalDeviceManagementState = staticCompositionLocalOf { DeviceManagementState(checkingLocalState = false) }
val LocalDeviceManagementCheckIn = staticCompositionLocalOf<() -> Unit> { {} }

/** Like maintenance, restriction replaces navigation so internal players dispose.
 * Unknown/offline enrollment is informational, not a new activation requirement. */
@Composable
fun DeviceManagementGate(state: DeviceManagementState, onDismissMessage: (String) -> Unit, content: @Composable () -> Unit) {
    if (state.checkingLocalState || state.restricted) {
        BackHandler { /* A known provider restriction cannot expose playback. */ }
        Box(Modifier.fillMaxSize().background(Color(0xFF090D15)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 620.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(when (state.policy) { "suspended" -> "Service suspended"; "blocked" -> "Device blocked"; "removed" -> "Device enrollment removed"; else -> "Checking device status" },
                    color = Color.White, fontSize = 28.sp, textAlign = TextAlign.Center)
                if (state.restricted) {
                    Text("Contact your provider. Your login, profiles and saved favourites have not been erased.", color = Color(0xFFD5DBE5), fontSize = 18.sp, textAlign = TextAlign.Center)
                    Text("Device ID: ${state.installationId}", color = Color(0xFF9AA8BC), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(state.status, color = Color(0xFF9AA8BC), fontSize = 13.sp, textAlign = TextAlign.Center)
                } else CircularProgressIndicator()
            }
        }
        return
    }
    content()
    val message = state.message?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
    if (message != null) {
        LaunchedEffect(message.id, message.expiresAtMs) {
            delay((message.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0))
            onDismissMessage(message.id)
        }
        AlertDialog(onDismissRequest = { onDismissMessage(message.id) },
            title = { Text("Message from your provider") }, text = { Text(message.text, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).focusable()) },
            confirmButton = { TextButton(onClick = { onDismissMessage(message.id) }) { Text("Dismiss") } })
    }
}

/** Read-only support information: no credentials or remote update controls. */
@Composable
fun DeviceManagementStatusInfo() {
    val state = LocalDeviceManagementState.current
    val checkIn=LocalDeviceManagementCheckIn.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Provider device management · ${state.status}", color = TextSecondary, fontSize = 12.sp)
        if (state.installationId.isNotBlank()) Text("Device ID: ${state.installationId}", color = TextSecondary, fontSize = 12.sp)
        TextButton(onClick=checkIn,enabled=state.installationId.isNotBlank()) { Text("Check in / retry management now") }
    }
}
