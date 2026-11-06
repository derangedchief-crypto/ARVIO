package com.extreme.myhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HubTheme {
                Surface(Modifier.fillMaxSize(), color = HubColors.background) { HubScreen() }
            }
        }
    }
}

@Composable
private fun HubScreen(model: HubModel = viewModel(), updates: HubUpdates = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(updates, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { updates.check(); kotlinx.coroutines.awaitCancellation() }
    }
    LaunchedEffect(model, lifecycle, state.route, state.session, state.license?.optBoolean("allowed")) {
        if (state.session != null && state.license?.optBoolean("allowed") == true)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeVisible() }
    }
    androidx.activity.compose.BackHandler(state.canBack && !state.busy) { model.back() }
    if (state.session == null || state.license?.optBoolean("allowed") != true) {
      Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("grid"); Text("My Hub", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
         if (state.session != null) TextButton(onClick = model::logout, enabled = !state.busy) { Text("Sign out") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { MessageBanner(it, true) }
         state.notice?.let { MessageBanner(it) }
         TextButton(onClick = { updates.check(true) }, enabled = !updateState.busy) { Text("Check for My Hub updates") }
        when {
            state.session == null -> Login(state.busy, model::login)
            else -> License(state, model::activate, model::refreshLicense)
        }
      }
    } else {
                WorkspaceFrame(state, model::navigate, model::back, { model.open(state.route, state.page, state.search, state.filters) }) {
                    if (state.route.startsWith("devices/")) {
                        state.syncError?.let { MessageBanner(it) }
                        state.inspection?.let { inspection ->
                            val sections=inspection.getJSONObject("sections")
                            val values=sections.keys().asSequence().map { sections.getJSONObject(it).getString("state") }.toList()
                            val device=inspection.getJSONObject("device")
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                StatusPill(if(device.optString("presence")=="online") "Online" else title(device.optString("presence")))
                                Text(when {
                                    values.any { it=="offline" } -> "Offline · Last-known data"
                                    values.any { it=="restricted" } -> "Device access paused"
                                    values.any { it=="syncing" || it=="waiting" } -> "Syncing device data…"
                                    values.any { it=="unavailable" } -> "Some data unavailable · Retrying"
                                    values.any { it=="unsupported" } -> "Some data requires an app update"
                                    else -> "Device data updates automatically"
                                },style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
                            }
                            val times=sections.keys().asSequence().mapNotNull { key ->
                                sections.getJSONObject(key).optString("updated_at").takeIf { it.isNotBlank() && it!="null" }
                            }.toList()
                            if(times.isNotEmpty()) Text("Last responses (UTC): ${times.minOrNull()} – ${times.maxOrNull()}",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
                        }
                    }
                    state.pending?.let { pending -> DetailCard("Previous operation needs review") {
                        Text("The server result was not confirmed: ${pending.route}. Review its current state before sending another change.")
                        Action("Review current state", state.busy) { model.open(pending.refresh) }
                        if (pending.key != null) Action("Retry original operation safely", state.busy) { model.retryPending() }
                        ConfirmAction("I have verified the result", state.busy) { model.acknowledgePending() }
                    } }
                    val data = state.data
                    if (data == null) {
                        if (state.busy) EmptyState("Loading your workspace", "Fetching the latest information…", "activity")
                        else Action("Try again", false) { model.open(state.route, state.page, state.search, state.filters) }
                    } else when {
                        state.route == "overview" -> Dashboard(data, state.session!!.name, state.busy, onlineDevices = { model.open("devices", filters = mapOf("presence" to "online")) }) { model.open(it) }
                        state.route == "more" -> {
                            WorkspaceMenu(state, model)
                            Action("Check for My Hub updates", updateState.busy) { updates.check(true) }
                            Text("My Hub ${BuildConfig.VERSION_NAME.removeSuffix("-dev")}", color = HubColors.muted)
                        }
                        state.route in listOf("customers", "devices", "audit") -> Records(state, model)
                        state.route == "trial-setup" -> TrialSettings(data, state.busy, model)
                        state.route == "app-brand" -> {
                            BrandSettings(data, state.busy, model)
                            data.rows("artifacts").forEach { artifact -> DetailCard("${artifact.text("channel")} · ${artifact.text("version_name")}") {
                                Details(artifact, "package_name", "apk_sha256")
                                if (artifact.optBoolean("download_ready")) ArtifactDownload(artifact.getString("artifact_url"), "Brand-${artifact.text("version_name")}", state, model, artifact.getString("apk_sha256"))
                            } }
                        }
                        state.route == "addons" -> AddonSettings(data, state.busy, model)
                        state.route == "renewals/settings" -> Renewals(state, model)
                        state.route.substringBefore('/') in listOf("library", "device-profiles", "device-defaults", "device-setups", "preference-profiles", "settings-presets", "category-layout-templates") -> Library(state, model)
                        state.route.substringBefore('/') in listOf("account", "trial-contacts", "recovery-holds") -> AccountControls(state, model)
                        state.route.matches(Regex("customers/[a-f0-9]{32}")) -> Customer(data, state.busy, model)
                        state.route.matches(Regex("devices/[a-f0-9]{32}")) -> Device(data, state.busy, model)
                        state.route.startsWith("devices/") -> DeviceControls(state, model)
                    }
                    Spacer(Modifier.height(16.dp))
                }
    }
    HubUpdatePrompt(updateState, updates)
}

@Composable
internal fun Login(busy: Boolean, submit: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { HubIcon("grid", Modifier.size(64.dp), HubColors.mint) }
        Text("YOUR BUSINESS. CONNECTED.", style = MaterialTheme.typography.labelSmall, color = HubColors.muted)
        Text("Welcome to My Hub.", style = MaterialTheme.typography.headlineLarge)
        Text("Your customers, devices and business.\nOne workspace, wherever you are.", color = HubColors.muted)
        OutlinedTextField(username, { username = it.take(150) }, label = { Text("Reseller username") }, leadingIcon = { HubIcon("person") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrect = false), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it.take(256) }, label = { Text("Password") }, leadingIcon = { HubIcon("lock") }, trailingIcon = { IconButton(onClick = { visible = !visible }, modifier = Modifier.semantics { contentDescription = if (visible) "Hide password" else "Show password" }) { HubIcon("eye") } }, singleLine = true, visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { submit(username, password); password = "" }, enabled = !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = MaterialTheme.shapes.medium) { Text("Sign in to your workspace"); Spacer(Modifier.width(8.dp)); HubIcon("chevron", Modifier.size(16.dp)) }
        Text("Use your existing reseller-panel credentials", style = MaterialTheme.typography.bodySmall, color = HubColors.muted)
    }
}

@Composable
private fun License(state: HubState, activate: (String) -> Unit, refresh: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Activate My Hub", style = MaterialTheme.typography.headlineSmall)
        Text("Use the My Hub key issued for your reseller account. You can also activate it from My Hub in the reseller panel.")
        state.license?.let { Text("License: ${it.optString("state")}") }
        OutlinedTextField(key, { key = it.take(80) }, label = { Text("My Hub key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { activate(key); key = "" }, enabled = !state.busy && key.isNotBlank()) { Text("Activate") }
        OutlinedButton(onClick = refresh, enabled = !state.busy) { Text("Check access") }
    }
}

@Composable
internal fun Records(state: HubState, model: HubModel) {
    val data = state.data!!
    var search by rememberSaveable(state.route) { mutableStateOf(state.search) }
    var create by remember(state.route) { mutableStateOf(false) }
    var policy by remember(state.route, state.filters) { mutableStateOf(state.filters["policy"] ?: "") }
    var visibility by remember(state.route, state.filters) { mutableStateOf(state.filters["visibility"] ?: "current") }
    var presence by remember(state.route, state.filters) { mutableStateOf(state.filters["presence"] ?: "") }
    var showFilters by rememberSaveable(state.route) { mutableStateOf(false) }
    Text(if (state.route == "devices") "YOUR FLEET" else "YOUR BUSINESS", style = MaterialTheme.typography.labelSmall, color = HubColors.muted)
    Text(label(state.route), style = MaterialTheme.typography.headlineMedium)
    if (state.route == "devices" && state.filters["presence"] == "online") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Online devices only", color = HubColors.mint)
            TextButton(onClick = { model.open("devices", search = state.search, filters = state.filters - "presence") }, enabled = !state.busy) { Text("Show all") }
        }
    }
    if (state.route != "audit") {
        OutlinedTextField(search, { search = it.take(120) }, label = { Text("Search ${state.route}") }, leadingIcon = { HubIcon("search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (state.route == "devices") {
            TextButton(onClick = { showFilters = !showFilters }) { HubIcon("filter", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (showFilters) "Hide filters" else "Access & history filters") }
            if (showFilters) {
                Choice("Access", policy, listOf("" to "All current policies") + pairs("active", "suspended", "blocked", "removed")) { policy = it }
                Choice("Device history", visibility, pairs("current", "all")) { visibility = it }
                Choice("Connection", presence, listOf("" to "All connections", "online" to "Online only")) { presence = it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.open(state.route, search = search, filters = if (state.route == "devices") state.filters + mapOf("policy" to policy, "visibility" to visibility, "presence" to presence) else state.filters) }, enabled = !state.busy) { Text("Search") }
            if (state.route == "customers") OutlinedButton(onClick = { create = true }, enabled = !state.busy) { Text("Add customer") }
        }
    }
    if (create) EditDialog("New customer", listOf("name", "notes", "external_reference"), JSONObject(), { create = false }) { fields -> create = false; model.save("customers", fields) }
    if (data.rows().isEmpty()) EmptyState("No records found", "Try a different search or add your first record.")
    data.rows().forEach { row ->
        if (state.route == "audit") DetailCard(title(row.text("action"))) { Details(row, "created_at") }
        else Surface(onClick = { model.open("${state.route}/${row.getString("id")}") }, enabled = !state.busy, shape = MaterialTheme.shapes.large, color = HubColors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(if (state.route == "devices") "device" else "person", if (state.route == "devices") HubColors.mint else HubColors.blue)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val customer = CustomerIdentity.from(row)
                        Text(if (state.route == "devices") row.text("label") else customer.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (state.route == "devices") row.text("customer_name", "Customer") else customer.subtitle, style = MaterialTheme.typography.bodySmall, color = HubColors.muted)
                    }
                    HubIcon("chevron", Modifier.size(16.dp), HubColors.muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Notes", style = MaterialTheme.typography.labelSmall, color = HubColors.muted)
                    val panelNotes = when (row.optString("panel_notes_state")) {
                        "current" -> if (row.isNull("panel_notes")) "Panel notes unavailable" else row.optString("panel_notes").ifEmpty { "—" }
                        "not_linked" -> "—"
                        else -> "Panel notes unavailable"
                    }
                    Text(panelNotes, style = MaterialTheme.typography.bodySmall)
                }
                if (state.route == "devices") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(presenceLabel(row.text("presence")), if (row.text("presence") in listOf("online", "idle")) HubColors.mint else HubColors.muted)
                    StatusPill(title(row.text("policy", "Unknown")), if (row.text("policy") == "active") HubColors.blue else HubColors.amber)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { model.open(state.route, state.page - 1, state.search, state.filters) }, enabled = !state.busy && state.page > 1) { Text("Previous") }
        Text("${state.page} / ${data.optInt("pages", 1)}", modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = { model.open(state.route, state.page + 1, state.search, state.filters) }, enabled = !state.busy && state.page < data.optInt("pages", 1)) { Text("Next") }
    }
}

@Composable
private fun Customer(data: JSONObject, busy: Boolean, model: HubModel) {
    var edit by remember(data) { mutableStateOf(false) }
    var addDevice by remember(data) { mutableStateOf(false) }
    val identity = CustomerIdentity.from(data)
    Text(identity.name, style = MaterialTheme.typography.headlineSmall)
    if (identity.subtitle != "Customer") Text(identity.subtitle, style = MaterialTheme.typography.bodySmall, color = HubColors.muted)
    Facts(data, listOf("notes", "external_reference", "created_at"))
    Action("Customer devices", busy) { model.open("devices", filters = mapOf("customer_id" to data.getString("id"))) }
    Button(onClick = { edit = true }, enabled = !busy) { Text("Edit customer") }
    OutlinedButton(onClick = { addDevice = true }, enabled = !busy) { Text("Add device") }
    if (edit) EditDialog("Edit customer", listOf("name", "notes", "external_reference"), data, { edit = false }) { fields -> edit = false; model.save("customers/${data.getString("id")}", fields) }
    if (addDevice) EditDialog("Add device", listOf("label", "notes"), JSONObject(), { addDevice = false }) { fields -> addDevice = false; model.save("devices", fields.put("customer_id", data.getString("id"))) }
}

@Composable
internal fun Device(data: JSONObject, busy: Boolean, model: HubModel) {
    val id = data.getString("id")
    var edit by remember(data) { mutableStateOf(false) }
    var action by remember(data) { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile("device")
        Text(data.getString("label"), style = MaterialTheme.typography.headlineMedium)
        Text(data.text("customer_name"), color = HubColors.muted)
        StatusPill(presenceLabel(data.text("presence")), if (data.text("presence") in listOf("online", "idle")) HubColors.mint else HubColors.muted)
    }
    DetailCard("Device information") { Facts(data, listOf("policy", "model", "app_version", "last_seen_at", "notes")) }
    Button(onClick = { edit = true }, enabled = !busy) { Text("Edit device") }
    if (edit) EditDialog("Edit device", listOf("label", "notes"), data, { edit = false }) { fields -> edit = false; model.save("devices/$id", fields) }
    if (data.optString("policy") in listOf("active", "suspended")) OutlinedButton(onClick = { action = if (data.getString("policy") == "active") "suspend" else "activate" }, enabled = !busy) { Text(if (data.getString("policy") == "active") "Suspend device" else "Reactivate device") }
    SectionHeading("Remote controls", "Actions run when the device checks in.")
    val capabilities = data.optJSONArray("capabilities") ?: JSONArray()
    val supported = (0 until capabilities.length()).map { capabilities.getString(it) }
    val quick = listOf("request_checkin", "refresh_playlist", "get_diagnostics")
    quick.filter { it in supported }.forEach { kind ->
        Action(label(kind), busy || data.optString("policy") != "active") { action = kind }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced commands" else "Advanced commands") }
    if (advanced) listOf("reload_epg", "restart_app", "clear_cache", "get_settings", "get_sources", "get_preferences", "backup_favourites").filter { it in supported }.forEach { kind ->
        Action(label(kind), busy || data.optString("policy") != "active") { action = kind }
    }
    TextButton(onClick = { model.open("devices/$id/commands") }, enabled = !busy) { Text("Command history") }
    SectionHeading("Device workspace")
    listOf("playback" to "Playback controls", "preferences" to "App preferences", "sources" to "Playlists & sources", "category-layout" to "Category layout", "favourites" to "Favourites & backups", "setup" to "Apply a saved setup", "support" to "Diagnostics & reports").forEach { (section, caption) -> MenuRow(title(section), caption, if (section == "support") "shield" else "settings", busy) { model.open("devices/$id/$section") } }
    if ("get_categories" in supported && "get_category_layout" !in supported) Action("Legacy category controls", busy) { model.open("devices/$id/categories") }
    action?.let { selected -> AlertDialog(onDismissRequest = { action = null }, title = { Text(label(selected)) }, text = { Text("Send this action to ${data.getString("label")}? Device actions complete after the app checks in.") }, confirmButton = {
        TextButton(onClick = {
            action = null
            if (selected in listOf("suspend", "activate")) model.save("devices/$id", JSONObject().put("policy", if (selected == "suspend") "suspended" else "active"))
            else model.deviceCommand(id, selected)
        }) { Text("Confirm") }
    }, dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } }) }
}

@Composable
private fun EditDialog(title: String, fields: List<String>, data: JSONObject, close: () -> Unit, save: (JSONObject) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it, if (data.isNull(it)) "" else data.optString(it)) } } }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { field -> OutlinedTextField(values[field] ?: "", { values[field] = it.take(if (field == "notes") 2000 else if (field == "external_reference") 128 else 120) }, label = { Text(label(field)) }, singleLine = field != "notes", modifier = Modifier.fillMaxWidth()) }
        }
    }, confirmButton = { TextButton(onClick = { save(JSONObject().apply { fields.forEach { put(it, values[it]) } }) }, enabled = fields.none { it in listOf("name", "label") && values[it].isNullOrBlank() }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

private fun label(value: String) = value.replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun presenceLabel(value: String) = when (value) { "online" -> "Connected"; "idle" -> "Connected · idle"; "not_enrolled" -> "Awaiting enrollment"; "" -> "Unknown"; else -> title(value) }
@Composable private fun Facts(data: JSONObject, fields: List<String>) {
    Details(data, *fields.toTypedArray())
}
@Composable private fun Rows(rows: JSONArray?, content: @Composable (JSONObject) -> Unit) {
    if (rows != null) for (index in 0 until rows.length()) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content(rows.getJSONObject(index)) }
    }
}
