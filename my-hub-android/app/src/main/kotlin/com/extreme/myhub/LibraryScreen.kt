package com.extreme.myhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

internal val sortChoices = pairs("provider", "name", "number")
internal val bufferChoices = listOf("0" to "Off", "2" to "2 seconds", "5" to "5 seconds", "10" to "10 seconds")

@Composable internal fun Library(state: HubState, model: HubModel) {
    val route = state.route; val data = state.data!!
    var form by remember { mutableStateOf<Form?>(null) }
    var layout by remember { mutableStateOf<JSONObject?>(null) }
    var layoutNew by remember { mutableStateOf(false) }
    var search by remember(route) { mutableStateOf(state.search) }
    fun saveProfile(row: JSONObject?) {
        val definition = row?.optJSONObject("definition") ?: JSONObject()
        val layouts = data.rows("_layouts"); val preferences = data.rows("_preferences")
        form = Form(if (row == null) "New saved setup" else "Edit saved setup", listOf(
            Field("name", value = row?.text("name") ?: "", max = 80),
            Field("layout", "Category layout", definition.optJSONObject("layout")?.text("id") ?: "", listOf("" to "Leave categories unchanged") + layouts.map { it.getString("id") to "${it.getString("name")} · v${it.getInt("version")}" }, required = false),
            Field("preferences", "Settings profile", definition.optJSONObject("settings")?.text("id") ?: "", listOf("" to "Leave preferences unchanged") + preferences.map { it.getString("id") to "${it.getString("name")} · v${it.getInt("version")}" }, required = false),
            Field("sort", "Channel sorting", definition.text("sort_order"), listOf("" to "Unchanged") + sortChoices, required = false),
            Field("buffer", "Live prebuffer", definition.text("prebuffer_seconds"), listOf("" to "Unchanged") + bufferChoices, required = false)
        )) { v ->
            val body = JSONObject().put("name", v["name"]).put("expected_version", row?.optInt("version") ?: 0)
            layouts.find { it.getString("id") == v["layout"] }?.let { body.put("layout_template_id", it.getString("id")).put("layout_version", it.getInt("version")) }
            preferences.find { it.getString("id") == v["preferences"] }?.let { body.put("settings_profile_id", it.getString("id")).put("settings_version", it.getInt("version")) }
            if (!v["sort"].isNullOrBlank()) body.put("sort_order", v["sort"])
            if (!v["buffer"].isNullOrBlank()) body.put("prebuffer_seconds", v["buffer"])
            model.submit("device-profiles" + (row?.let { "/${it.getString("id")}" } ?: ""), body, "device-profiles")
        }
    }
    fun preset(row: JSONObject?) {
        form = Form("Sorting preset", listOf(Field("name", value = row?.text("name") ?: "", max = 80), Field("sort_order", value = row?.text("sort_order") ?: "provider", choices = sortChoices))) { v -> model.submit("settings-presets" + (row?.let { "/${it.getString("id")}" } ?: ""), JSONObject(v), route) }
    }
    fun preference(row: JSONObject?) {
        val rules = data.getJSONObject("rules"); val values = row?.optJSONObject("values") ?: JSONObject()
        form = Form("Settings profile", listOf(Field("name", value = row?.text("name") ?: "", max = 80)) + rules.keys().asSequence().map { k ->
            val options = rules.getJSONArray(k)
            Field(k, value = values.text(k), choices = listOf("" to "Unmanaged · keep local choice") + (0 until options.length()).map { options.getString(it) to options.getString(it) }, required = false)
        }.toList()) { v ->
            val selected = JSONObject(); v.filter { it.key != "name" && it.value.isNotBlank() }.forEach { (k, value) -> selected.put(k, value) }
            model.submit("preference-profiles" + (row?.let { "/${it.getString("id")}" } ?: ""), JSONObject().put("name", v["name"]).put("values", selected).put("expected_version", row?.optInt("version") ?: 0), route)
        }
    }
    Text(routeTitle(route), style = MaterialTheme.typography.headlineMedium)
    if (route == "library") {
        Text("A consistent experience across your fleet.", color = HubColors.muted)
        listOf(Triple("device-profiles", "Saved setups", "Reusable configurations for your devices"), Triple("device-defaults", "Automatic setup", "Defaults for newly enrolled devices"), Triple("device-setups", "Setup activity", "Track queued and completed setup jobs"), Triple("preference-profiles", "Preference profiles", "App settings, ready to apply"), Triple("settings-presets", "Sorting presets", "Keep channels in the right order"), Triple("category-layout-templates", "Category layouts", "Organize the viewing experience")).forEach { (path, name, description) -> MenuRow(name, description, if (path == "device-setups") "activity" else "library", state.busy) { model.open(path) } }
    } else if (route == "device-defaults") {
        val policy = data.optJSONObject("policy")
        policy?.let { Details(it, "name", "profile_version", "enabled", "revision") }
        val profiles = data.rows("_profiles")
        Text("Changes apply to newly admitted devices. Existing devices require an explicit setup action.")
        Action("Choose automatic setup", state.busy) {
            form = Form("Automatic setup", listOf(Field("profile", value = policy?.text("profile_id") ?: "", choices = profiles.map { it.getString("id") to "${it.getString("name")} · v${it.getInt("version")}" }), Field("enabled", value = if (policy?.flag("enabled") == true) "yes" else "no", choices = pairs("yes", "no")))) { v ->
                profiles.find { it.getString("id") == v["profile"] }?.let { p -> model.submit(route, JSONObject().put("profile_id", p.getString("id")).put("profile_version", p.getInt("version")).put("expected_revision", policy?.optInt("revision") ?: 0).put("enabled", v["enabled"] == "yes"), route) }
            }
        }
        if (policy?.flag("enabled") == true) ConfirmAction("Disable automatic setup", state.busy) { model.submit(route, JSONObject().put("profile_id", policy.getString("profile_id")).put("profile_version", policy.getInt("profile_version")).put("expected_revision", policy.getInt("revision")).put("enabled", false), route) }
    } else if (route.startsWith("device-setups/")) {
        Details(data, "profile_name", "profile_version", "status", "stage", "message", "created_at", "expires_at")
        ResultDetails(data.opt("stage_status")); ResultDetails(data.opt("results")); ResultDetails(data.opt("notes"))
        if (data.text("status") == "paused") Action("Resume original setup", state.busy) { model.submit(route, JSONObject().put("action", "resume"), route) }
        if (data.text("status") !in listOf("complete", "complete_with_notes", "cancelled")) ConfirmAction("Cancel setup", state.busy, "Already applied or running device work is not undone.") { model.submit(route, JSONObject().put("action", "cancel"), route) }
        Action("Refresh job", state.busy) { model.open(route) }
    } else {
        if (route != "device-setups") {
            OutlinedTextField(search, { search = it.take(80) }, label = { Text("Search saved items") }, modifier = Modifier.fillMaxWidth())
            Action("Search", state.busy) { model.open(route, search = search) }
            Action("Create ${when(route) { "device-profiles" -> "saved setup"; "category-layout-templates" -> "layout"; else -> "preset" }}", state.busy) {
                when (route) { "device-profiles" -> saveProfile(null); "settings-presets" -> preset(null); "preference-profiles" -> preference(null); else -> model.read("layout-catalogue") { catalogue -> layout = JSONObject().put("entries", catalogue.getJSONArray("entries")); layoutNew = true } }
            }
        }
        data.rows().forEach { row -> DetailCard(row.text("name", row.text("profile_name", "Setup"))) {
            Details(row, "version", "sort_order", "category_count", "device_label", "status", "stage", "message")
            if (route == "device-setups") Action("View setup", state.busy) { model.open("device-setups/${row.getString("id")}") }
            else {
                if (route == "device-profiles") ResultDetails(row.optJSONObject("definition")?.let { d -> JSONObject().put("layout", d.optJSONObject("layout")?.text("name") ?: "Unchanged").put("preferences", d.optJSONObject("settings")?.text("name") ?: "Unchanged").put("sort", d.opt("sort_order")).put("buffer", d.opt("prebuffer_seconds")) })
                Action("Edit", state.busy) { when (route) { "device-profiles" -> saveProfile(row); "settings-presets" -> preset(row); "preference-profiles" -> preference(row); else -> model.read("$route/${row.getString("id")}") { layout = it; layoutNew = false } } }
                ConfirmAction("Archive", state.busy) {
                    when(route) {
                        "device-profiles" -> model.submit("$route/${row.getString("id")}", JSONObject().put("name", row.getString("name")).put("expected_version", row.getInt("version")).put("archived", true), route)
                        "preference-profiles" -> model.submit("$route/${row.getString("id")}", JSONObject().put("name", row.getString("name")).put("values", row.getJSONObject("values")).put("expected_version", row.getInt("version")).put("archived", true), route)
                        else -> model.submit("$route/${row.getString("id")}/archive", if (route == "category-layout-templates") JSONObject().put("expected_version", row.getInt("version")) else JSONObject(), route)
                    }
                }
            }
        } }
        Pager(state, model)
    }
    NativeForm(form) { form = null }
    layout?.let { initial -> LayoutEditor(initial.rows("entries"), true, initial.text("name"), { layout = null }) { name, rows ->
        val body = JSONObject().put("name", name).put("entries", JSONArray(rows.map { JSONObject().put("name", it.getString("name")).put("hidden", it.getBoolean("hidden")) }))
        if (!layoutNew) body.put("expected_version", initial.getInt("version"))
        model.submit("category-layout-templates" + if (layoutNew) "" else "/${initial.getString("id")}", body, "category-layout-templates"); layout = null
    } }
}

@Composable internal fun LayoutEditor(initial: List<JSONObject>, portable: Boolean, initialName: String, close: () -> Unit, save: (String, List<JSONObject>) -> Unit) {
    val rows = remember { mutableStateListOf<JSONObject>().apply { addAll(initial.map { JSONObject(it.toString()) }) } }
    var name by remember { mutableStateOf(initialName) }; var newName by remember { mutableStateOf("") }; var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.fillMaxSize(), color = HubColors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (portable) "Category layout template" else "Review category order & visibility", style = MaterialTheme.typography.titleLarge)
            if (portable) OutlinedTextField(name, { name = it.take(80) }, label = { Text("Template name") })
            OutlinedTextField(search, { search = it }, label = { Text("Find category") })
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(rows) { index, row ->
                    val caption = row.text(if (portable) "name" else "label")
                    if (caption.contains(search, true)) Column {
                        Row { Checkbox(!row.optBoolean("hidden"), { visible -> rows[index] = JSONObject(row.toString()).put("hidden", !visible) }); Text(caption, Modifier.weight(1f).padding(top = 12.dp)) }
                        Row {
                            TextButton(onClick = { rows.add(index - 1, rows.removeAt(index)) }, enabled = index > 0) { Text("↑") }
                            TextButton(onClick = { rows.add(index + 1, rows.removeAt(index)) }, enabled = index < rows.lastIndex) { Text("↓") }
                            TextButton(onClick = { rows.add(0, rows.removeAt(index)) }, enabled = index > 0) { Text("Top") }
                            if (portable) TextButton(onClick = { rows.removeAt(index) }) { Text("Remove") }
                        }
                    }
                }
            }
            if (portable) { OutlinedTextField(newName, { newName = it.take(80) }, label = { Text("Add category name") }); TextButton(onClick = { rows.add(JSONObject().put("name", newName.trim()).put("hidden", false)); newName = "" }, enabled = newName.isNotBlank() && rows.none { it.text("name") == newName.trim() } && rows.size < 2000) { Text("Add category") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("Cancel") }
                Button(onClick = { save(name, rows.toList()) }, enabled = (!portable || name.isNotBlank()) && rows.isNotEmpty() && rows.any { !it.optBoolean("hidden") }) { Text(if (portable) "Save layout" else "Apply reviewed layout") }
            }
        }
      }
    }
}
