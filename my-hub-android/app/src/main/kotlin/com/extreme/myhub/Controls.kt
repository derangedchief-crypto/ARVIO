package com.extreme.myhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.rows(key: String = "items"): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.text(key: String, fallback: String = "") = if (isNull(key)) fallback else optString(key, fallback)
internal fun title(key: String) = key.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
internal fun pairs(vararg values: String) = values.map { it to title(it) }
internal fun JSONObject.flag(key: String) = opt(key) == true || opt(key)?.toString() == "1"

internal data class Field(val key: String, val label: String = title(key), val value: String = "", val choices: List<Pair<String, String>> = emptyList(), val required: Boolean = true, val secret: Boolean = false, val max: Int = 2000)
internal data class Form(val title: String, val fields: List<Field>, val save: (Map<String, String>) -> Unit)

@Composable internal fun Choice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FilledTonalButton(onClick = { expanded = true }, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(choices.find { it.first == value }?.second ?: "Choose…", modifier = Modifier.weight(1f)); HubIcon("down", Modifier.size(18.dp)) }
        if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { (key, caption) -> TextButton(onClick = { change(key); expanded = false }, modifier = Modifier.fillMaxWidth()) { Text((if (value == key) "✓ " else "") + caption) } }
            }
        }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } })
    }
}

@Composable internal fun NativeForm(form: Form?, close: () -> Unit) {
    if (form == null) return
    val values = remember(form) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.value) } } }
    AlertDialog(onDismissRequest = close, title = { Text(form.title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            form.fields.forEach { f ->
                if (f.choices.isNotEmpty()) Choice(f.label, values[f.key] ?: "", f.choices) { values[f.key] = it }
                else OutlinedTextField(values[f.key] ?: "", { values[f.key] = it.take(f.max) }, label = { Text(f.label) }, visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth(), minLines = if (f.key == "notes") 3 else 1)
            }
        }
    }, confirmButton = { TextButton(onClick = { form.save(values.toMap()); close() }, enabled = form.fields.none { it.required && values[it.key].isNullOrBlank() }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable internal fun Action(label: String, busy: Boolean, run: () -> Unit) { FilledTonalButton(onClick = run, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { Text(label, modifier = Modifier.weight(1f)); HubIcon("chevron", Modifier.size(16.dp)) } }
@Composable internal fun DetailCard(name: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = HubColors.surface), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(name, style = MaterialTheme.typography.titleMedium); content() } }
}
@Composable internal fun Details(row: JSONObject, vararg fields: String) { fields.filter { row.has(it) && !row.isNull(it) && row.opt(it)?.toString()?.isNotBlank() == true }.forEach { key ->
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title(key), style = MaterialTheme.typography.labelSmall, color = HubColors.muted)
        Text(row.opt(key)?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium)
    }
} }
/** Structured read-only results, never an editable JSON console. */
@Composable internal fun ResultDetails(value: Any?, depth: Int = 0) {
    when (value) {
        is JSONObject -> value.keys().asSequence().toList().forEach { key ->
            val child = value.opt(key)
            if (child is JSONObject || child is JSONArray) { Text(title(key), style = MaterialTheme.typography.labelLarge); Column(Modifier.padding(start = 8.dp)) { if (depth < 6) ResultDetails(child, depth + 1) } }
            else if (child != JSONObject.NULL) Text("${title(key)}: $child")
        }
        is JSONArray -> for (i in 0 until value.length()) ResultDetails(value.opt(i), depth + 1)
        null, JSONObject.NULL -> Unit
        else -> Text(value.toString())
    }
}
@Composable internal fun Pager(state: HubState, model: HubModel, data: JSONObject = state.data!!) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { model.open(state.route, state.page - 1, state.search, state.filters) }, enabled = !state.busy && state.page > 1) { Text("Previous") }
        Text("${state.page} / ${data.optInt("pages", 1)}", Modifier.padding(top = 12.dp))
        TextButton(onClick = { model.open(state.route, state.page + 1, state.search, state.filters) }, enabled = !state.busy && state.page < data.optInt("pages", 1)) { Text("Next") }
    }
}
@Composable internal fun ConfirmAction(label: String, busy: Boolean, message: String = label, run: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Action(label, busy) { confirm = true }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(label) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { confirm = false; run() }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
