package com.arflix.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arflix.tv.util.StorageUsage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local-only measurement. Opening Settings never scans or changes storage. */
@Composable
internal fun StorageUsageButton() {
    val context = LocalContext.current.applicationContext
    var visible by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<StorageUsage.Report?>(null) }
    TextButton(onClick = { report = null; visible = true }) { Text("Storage usage") }
    if (!visible) return
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { StorageUsage.scan(context.dataDir) }
    }
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Storage usage") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val measured = report
                if (measured == null) Text("Measuring local app files…") else {
                    Text("Private files: ${storageSize(measured.totalBytes)} (approximate)")
                    StorageUsage.labels.forEach { (key, label) ->
                        Text("$label: ${storageSize(measured.bytes.getValue(key))}")
                    }
                    if (!measured.complete) Text("Partial scan; some files could not be measured. Totals are a lower bound.")
                    Text("Read-only. Nothing was cleared or uploaded. Figures may change during sync and exclude the installed APK, Android-managed space and external storage.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } }
    )
}

private fun storageSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
