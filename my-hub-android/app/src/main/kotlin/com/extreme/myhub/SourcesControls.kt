package com.extreme.myhub

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

internal object SourceForms {
    fun entry(row: JSONObject): JSONObject = JSONObject().apply {
        listOf("ref", "kind", "name", "enabled", "replace_url", "replace_mac", "replace_guides", "xtream_host", "xtream_username", "xtream_password").filter(row::has).forEach { put(it, row.get(it)) }
    }
    fun replacements(row: JSONObject, values: Map<String, String>): JSONObject = JSONObject(row.toString()).apply {
        put("name", values.getValue("name")); put("enabled", values["enabled"] == "yes")
        listOf("replace_url", "replace_mac", "xtream_host", "xtream_username", "xtream_password").forEach { key -> if (!values[key].isNullOrBlank()) put(key, values[key]) else remove(key) }
        if (values["guides_action"] == "replace") put("replace_guides", JSONArray(values["guides"].orEmpty().lines().filter(String::isNotBlank)))
        else remove("replace_guides")
    }
}

@Composable internal fun SourcesControls(state: HubState, model: HubModel) {
    val route = state.route; val data = state.data!!; val id = route.split('/')[1]; val base = "devices/$id/sources"
    var form by remember(route, data) { mutableStateOf<Form?>(null) }
    var preview by remember(route, data) { mutableStateOf<JSONObject?>(null) }
    val entries = remember(route, data) { mutableStateListOf<JSONObject>().apply { addAll(data.rows("entries")) } }
    DisposableEffect(form,preview,route) {
        model.holdDeviceUpdates(form!=null||preview!=null||route!=base)
        onDispose { model.holdDeviceUpdates(false) }
    }
    fun edit(index: Int, row: JSONObject) {
        val canonical = row.optBoolean("canonical")
        form = Form("Source · ${if (canonical) "verified activation" else row.text("kind")}", listOf(Field("name", value = row.text("name"), max = 80), Field("enabled", value = if (row.optBoolean("enabled", true)) "yes" else "no", choices = pairs("yes", "no"))) +
            (if (!canonical) listOf(Field("replace_url", "Replace URL (blank keeps existing)", row.text("replace_url"), required = false, secret = true)) + if (row.text("kind") == "stalker") listOf(Field("replace_mac", "Replace MAC", row.text("replace_mac"), required = false, secret = true)) else listOf(Field("xtream_host", value = row.text("xtream_host"), required = false, secret = true), Field("xtream_username", value = row.text("xtream_username"), required = false, secret = true), Field("xtream_password", value = row.text("xtream_password"), required = false, secret = true)) else emptyList()) +
            if (row.text("kind") == "m3u") listOf(Field("guides_action", "Guide URLs", if (row.has("replace_guides")) "replace" else "keep", pairs("keep", "replace")), Field("guides", "Replacement guide URLs · one per line; empty clears", value = row.optJSONArray("replace_guides")?.let { urls -> (0 until urls.length()).joinToString("\n") { urls.getString(it) } } ?: "", required = false, secret = true)) else emptyList()) { values ->
                val changed = SourceForms.replacements(row, values)
                if (index < 0) entries.add(changed) else entries[index] = changed
                preview = null
            }
    }
    Text("Sources sync automatically. Snapshot edits keep their original revision.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
    if (route == base) {
        Text(data.text("notice"))
        data.rows().forEach { row -> DetailCard(row.text("created_at")) {
            Details(row, "reason", "protected", "profile_ref")
            Action("Edit this snapshot", state.busy) { model.open("$base/${row.getString("id")}") }
            val latest = data.rows().firstOrNull { it.text("profile_ref") == row.text("profile_ref") }
            if (latest != null && latest.text("id") != row.text("id")) Action("Preview rollback to this checkpoint", state.busy) { model.prepare("$base/preview", JSONObject().put("snapshot_id", latest.getString("id")).put("rollback_id", row.getString("id"))) { preview = it } }
        } }
    } else {
        Text("The activation source stays first. Blank replacement fields keep private values. Preview before applying.")
        entries.forEachIndexed { index, row -> DetailCard(row.text("name")) {
            Details(row, "kind", "enabled", "canonical", "guide_count")
            Action("Edit source", state.busy) { edit(index, row) }
            if (!row.optBoolean("canonical")) {
                ConfirmAction("Remove source", state.busy) { entries.removeAt(index); preview = null }
                if (index > 1) Action("Move earlier", state.busy) { entries.add(index - 1, entries.removeAt(index)); preview = null }
            }
        } }
        if (entries.size < 7) listOf("m3u", "stalker").forEach { kind -> Action("Add $kind source", state.busy) { edit(-1, JSONObject().put("kind", kind).put("enabled", true)) } }
        Action("Preview source changes", state.busy) { model.prepare("$base/preview", JSONObject().put("snapshot_id", data.getString("id")).put("entries", JSONArray(entries.map(SourceForms::entry)))) { preview = it } }
    }
    preview?.let { p -> ResultDetails(p); ConfirmAction("Apply reviewed sources", state.busy, p.text("warning")) { model.deviceCommand(id, "apply_sources", JSONObject().put("draft_id", p.getString("id"))) } }
    NativeForm(form) { form = null }
}
