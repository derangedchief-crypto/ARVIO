package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.data.repository.MaintenanceState

/** Replace, rather than cover, navigation: all player surfaces are disposed. */
@Composable
fun MaintenanceGate(state: MaintenanceState, content: @Composable () -> Unit) {
    if (!state.active && !state.checking) {
        content()
        return
    }
    BackHandler { /* Back cannot expose navigation or resume playback. */ }
    Box(Modifier.fillMaxSize().background(Color(0xFF090D15)).focusable(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 620.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (state.active) "Under maintenance" else "Connecting to Extreme Hub",
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (state.active) {
                Text(state.message, color = Color(0xFFD5DBE5), fontSize = 18.sp, textAlign = TextAlign.Center)
                Text(if (state.connectionFailed) "Waiting for a connection to check maintenance status."
                    else "You’re still signed in. This screen will close automatically when maintenance ends.",
                    color = Color(0xFF9AA8BC), fontSize = 14.sp, textAlign = TextAlign.Center)
            } else CircularProgressIndicator(color = Color(0xFF86B8FF))
        }
    }
}
