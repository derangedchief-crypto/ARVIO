package com.extreme.myhub

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun DeviceControls(state: HubState, model: HubModel) {
    val route = state.route; val data = state.data!!; val id = route.split('/')[1]; val base = "devices/$id"
    var form by remember(route, data) { mutableStateOf<Form?>(null) }
    var preview by remember(route, data) { mutableStateOf<JSONObject?>(null) }
    var layout by remember(route, data) { mutableStateOf(false) }
    var layoutRows by remember(route, data) { mutableStateOf<List<JSONObject>?>(null) }
    DisposableEffect(form,preview,layout) {
        model.holdDeviceUpdates(form!=null||preview!=null||layout)
        onDispose { model.holdDeviceUpdates(false) }
    }
    Text(title(route.split('/')[2]), style = MaterialTheme.typography.headlineSmall)
    when {
        route.endsWith("/commands") -> {
            Action("Send customer message", state.busy) { form = Form("Customer message", listOf(Field("message", max = 1000), Field("priority", value = "normal", choices = pairs("normal", "urgent")))) { model.deviceCommand(id, "show_message", JSONObject(it)) } }
            data.rows().forEach { row -> DetailCard(title(row.text("kind"))) { Details(row, "status", "created_at", "finished_at"); ResultDetails(row.opt("result")); Action("Command details", state.busy) { model.open("$route/${row.getString("id")}") } } }
            Pager(state, model)
        }
        route.contains("/commands/") -> {
            ResultDetails(data)
            if (data.text("status") in listOf("pending", "delivered")) ConfirmAction("Cancel unstarted command", state.busy) { model.submit("$route/cancel", JSONObject(), route) }
        }
        route.endsWith("/playback") -> {
            Text("Playback settings sync automatically.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
            val snapshot = data.rows().firstOrNull { it.text("status") == "succeeded" && it.text("kind") in listOf("get_settings", "set_settings", "set_live_buffer") && it.optJSONObject("result")?.optJSONObject("snapshot") != null }?.getJSONObject("result")?.getJSONObject("snapshot")
            if (snapshot == null) Text("Waiting for the open app to send its current settings…")
            else {
                ResultDetails(snapshot)
                listOf("sort_order", "prebuffer_seconds").filter { it == "sort_order" || !snapshot.isNull("live_buffer_revision") }.forEach { key -> Action(if (key == "sort_order") "Change sorting" else "Change Live TV buffer", state.busy) {
                    form = Form(title(key), listOf(Field(key, value = if (key == "sort_order") snapshot.text(key) else snapshot.text("live_prebuffer_seconds", "0"), choices = if (key == "sort_order") sortChoices else bufferChoices))) { v -> model.deviceCommand(id, if (key == "sort_order") "set_settings" else "set_live_buffer", JSONObject(v).put("profile_ref", snapshot.getString("profile_ref")).put("expected_revision", snapshot.getString(if (key == "sort_order") "settings_revision" else "live_buffer_revision"))) }
                } }
                data.rows("_presets").forEach { preset -> ConfirmAction("Apply sorting: ${preset.text("name")}", state.busy) { model.deviceCommand(id, "set_settings", JSONObject().put("profile_ref", snapshot.getString("profile_ref")).put("expected_revision", snapshot.getString("settings_revision")).put("sort_order", preset.getString("sort_order"))) } }
            }
        }
        route.endsWith("/setup") -> {
            Text("Apply a versioned setup to this device. Completed stages are not undone if a later stage pauses.")
            data.rows("_profiles").forEach { p -> ConfirmAction("Apply ${p.text("name")} · v${p.optInt("version")}", state.busy) { model.submit(route, JSONObject().put("profile_id", p.getString("id")).put("profile_version", p.getInt("version")).put("confirmed", true), "device-setups") } }
        }
        route.endsWith("/preferences") -> {
            Text("Preferences sync automatically.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
            val snap = data.optJSONObject("snapshot"); val doc = snap?.optJSONObject("document")
            if (doc != null) {
                ResultDetails(doc.opt("values"))
                Action("Edit profile and overrides", state.busy) {
                    val rules = data.getJSONObject("rules")
                    form = Form("Review preferences", listOf(Field("base", "Base settings profile", doc.text("base_preset_id"), listOf("" to "No base profile") + data.rows("_profiles").map { it.getString("id") to it.getString("name") }, required = false)) + rules.keys().asSequence().map { key -> val choices = rules.getJSONArray(key); Field(key, value = doc.optJSONObject("overrides")?.text(key) ?: "", choices = listOf("" to "No device override") + (0 until choices.length()).map { choices.getString(it) to choices.getString(it) }, required = false) }.toList()) { v ->
                        val overrides = JSONObject(); v.filter { it.key != "base" && it.value.isNotBlank() }.forEach { (k, value) -> overrides.put(k, value) }
                        model.prepare("$route/preview", JSONObject().put("snapshot_id", snap.getString("command_id")).put("base_preset_id", v["base"]).put("overrides", overrides)) { preview = it }
                    }
                }
            }
            preview?.let { p -> ResultDetails(p); ConfirmAction("Apply reviewed preferences", state.busy) { model.deviceCommand(id, "apply_preferences", JSONObject().put("draft_id", p.getString("id"))) } }
        }
        route.endsWith("/categories") -> {
            Text("Categories load automatically for the selected source.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
            val catalog = data.rows().filter { it.text("kind") == "get_categories" && it.text("status") == "succeeded" && it.optJSONObject("result")?.optJSONObject("catalog") != null }.maxByOrNull { it.optJSONObject("result")?.optLong("received_at_us") ?: 0 }?.getJSONObject("result")?.getJSONObject("catalog")
            if (catalog != null) {
                fun readPage(page: Int, source: String = catalog.getString("source_ref")) { model.deviceCommand(id, "get_categories", JSONObject().put("profile_ref", catalog.getString("profile_ref")).put("source_ref", source).put("page", page)) }
                fun change(operation: String, ref: String? = null) { model.deviceCommand(id, "set_categories", JSONObject().put("profile_ref", catalog.getString("profile_ref")).put("source_ref", catalog.getString("source_ref")).put("expected_revision", catalog.getString("revision")).put("operation", operation).apply { if (ref != null) put("category_ref", ref) }) }
                catalog.rows("sources").forEach { source -> Action(source.text("label"), state.busy) { readPage(1, source.getString("ref")) } }
                Text("Snapshot page ${catalog.optInt("page")} / ${catalog.optInt("pages")}. Results update automatically.")
                catalog.rows().forEach { row -> DetailCard(row.text("label")) {
                    Details(row, "channels", "hidden", "position")
                    ConfirmAction(if (row.optBoolean("hidden")) "Show category" else "Hide category", state.busy) { change(if (row.optBoolean("hidden")) "show" else "hide", row.getString("ref")) }
                    if (catalog.optBoolean("order_editable")) listOf("move_up", "move_down", "move_top").forEach { op -> Action(title(op), state.busy) { change(op, row.getString("ref")) } }
                } }
                if (catalog.optInt("page") > 1) Action("Previous page", state.busy) { readPage(catalog.getInt("page") - 1) }
                if (catalog.optInt("page") < catalog.optInt("pages")) Action("Next page", state.busy) { readPage(catalog.getInt("page") + 1) }
                if (catalog.optBoolean("order_editable")) ConfirmAction("Reset category order", state.busy) { change("reset_order") }
            }
        }
        route.endsWith("/category-layout") -> {
            Text("Channel groups sync automatically.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
            val snap = data.optJSONObject("snapshot"); val catalog = data.optJSONObject("catalog")
            if (snap != null && catalog != null) {
                catalog.rows("sources").forEach { source -> Action("${source.text("label")}", state.busy) { model.inspectScope(snap.getString("profile_ref"), source.getString("ref")) } }
                Action("Edit category order and visibility", state.busy) { layout = true }
                data.rows("_templates").forEach { template -> Action("Preview ${template.text("name")}", state.busy) { model.layoutPreview(template.getString("id"), catalog) { preview = it } } }
                preview?.let { p ->
                    Text("Matched categories: ${p.optInt("matched")}. Unmatched categories keep their visibility and follow the matched categories.")
                    ResultDetails(p.opt("missing"))
                    Action("Review matched layout", state.busy) { layoutRows = p.rows(); layout = true }
                }
                if (layout) LayoutEditor(layoutRows ?: catalog.rows(), false, "", { layout = false; layoutRows = null }) { _, rows ->
                    model.deviceCommand(id, "apply_category_layout", JSONObject().put("profile_ref", snap.getString("profile_ref")).put("source_ref", snap.getString("source_ref")).put("expected_revision", snap.getString("revision")).put("order", JSONArray(rows.map { it.getString("ref") })).put("hidden", JSONArray(rows.filter { it.optBoolean("hidden") }.map { it.getString("ref") })).put("provider_order", false)); layout = false
                }
                ConfirmAction("Restore provider category order", state.busy) { model.deviceCommand(id, "apply_category_layout", JSONObject().put("profile_ref", snap.getString("profile_ref")).put("source_ref", snap.getString("source_ref")).put("expected_revision", snap.getString("revision")).put("order", JSONArray(catalog.rows().sortedBy { it.optInt("provider_position") }.map { it.getString("ref") })).put("hidden", JSONArray(catalog.rows().filter { it.optBoolean("hidden") }.map { it.getString("ref") })).put("provider_order", true)) }
            }
        }
        route.endsWith("/favourites") -> {
            Action("Create favourites backup", state.busy) { model.deviceCommand(id, "backup_favourites") }
            data.rows().forEach { row -> DetailCard(row.text("label", "Backup")) { Details(row, "channel_count", "group_count", "protected", "created_at"); Action("Review backup", state.busy) { model.open("$route/backups/${row.getString("id")}") } } }; Pager(state, model)
        }
        route.contains("/favourites/backups/") -> {
            Details(data, "label", "channel_count", "group_count", "protected", "created_at"); ResultDetails(data.opt("entries"))
            Action("Label & protection", state.busy) { form = Form("Backup protection", listOf(Field("label", value = data.text("label"), required = false, max = 80), Field("protected", value = if (data.flag("protected")) "yes" else "no", choices = pairs("yes", "no")))) { model.submit("$route/protect", JSONObject().put("label", it["label"]).put("protected", it["protected"] == "yes"), route) } }
            Action("Request fresh restore preview", state.busy) { model.submit("$route/preview", JSONObject(), route) }
            val p = data.optJSONObject("_preview")?.optJSONObject("preview"); val result = p?.optJSONObject("preview")
            if (result != null) {
                ResultDetails(result)
                if (result.optInt("matched_channels") + result.optInt("matched_groups") > 0) listOf("merge", "replace").filter { it == "merge" || (result.optJSONArray("missing_refs")?.length() ?: 0) == 0 }.forEach { mode -> ConfirmAction("Restore · $mode", state.busy) { model.submit("$route/restore", JSONObject().put("preview_id", p.getString("command_id")).put("mode", mode), route) } }
            }
        }
        route.contains("/sources") -> SourcesControls(state, model)
        route.endsWith("/support") -> {
            Text("Support reports sync automatically.",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
            ConfirmAction("Request consented support image", state.busy) { model.deviceCommand(id, "capture_app_screen") }
            data.rows().forEach { row -> DetailCard(title(row.text("kind"))) { Details(row, "bytes", "created_at", "expires_at"); ArtifactDownload("$route/${row.getString("id")}/download", row.text("kind"), state, model) } }
        }
    }
    NativeForm(form) { form = null }
}
