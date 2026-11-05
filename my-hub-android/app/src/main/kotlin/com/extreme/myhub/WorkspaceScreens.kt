package com.extreme.myhub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun TrialSettings(data: JSONObject, busy: Boolean, model: HubModel) {
    Text("App trials", style = MaterialTheme.typography.headlineSmall)
    Text(data.optString("status"))
    val blockers = data.optJSONArray("blockers") ?: JSONArray()
    for (index in 0 until blockers.length()) Text(blockers.getString(index))
    if (!data.optBoolean("global_model")) { Text("This build requires the current Hub self-service trial model."); return }
    val settings = data.getJSONObject("settings")
    val plans = data.getJSONArray("plans")
    var enabled by remember(data) { mutableStateOf(settings.getBoolean("enabled")) }
    var selected by remember(data) { mutableStateOf(settings.optInt("plan_id", 0)) }
    var daily by remember(data) { mutableStateOf(settings.optInt("daily_limit", 50).toString()) }
    var confirmed by remember(data) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(enabled, { enabled = it }, enabled = !busy)
        Text("Show the trial button in my app", modifier = Modifier.padding(top = 12.dp))
    }
    Text("Approved trial packages", style = MaterialTheme.typography.titleMedium)
    for (index in 0 until plans.length()) {
        val plan = plans.getJSONObject(index)
        FilterChip(selected == plan.getInt("id"), { selected = plan.getInt("id") }, label = { Text("${plan.getString("name")} · ${plan.opt("duration")} ${plan.optString("period")} · ${plan.opt("credits")} credits") }, enabled = !busy)
    }
    OutlinedTextField(daily, { daily = it.filter(Char::isDigit).take(4) }, label = { Text("Daily trial limit (1–${data.optInt("daily_max", 1000)})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, singleLine = true)
    Text("Signups collect first name and phone for your follow-up. No SMS verification.")
    if (enabled) Row {
        Checkbox(confirmed, { confirmed = it }, enabled = !busy)
        Text("I confirm enabling public trial signups and collecting these contact details.", modifier = Modifier.padding(top = 10.dp))
    }
    val pause = settings.getBoolean("enabled") && !enabled
    val valid = pause || (selected > 0 && (daily.toIntOrNull() ?: 0) in 1..data.optInt("daily_max", 1000) && (!enabled || confirmed))
    Button(onClick = {
        val body = JSONObject().put("expected_default_revision", settings.getInt("default_revision")).put("expected_offer_revision", settings.getInt("offer_revision"))
            .put("enabled", enabled).put("confirmed_public_mode", confirmed)
        if (!pause) {
            val plan = (0 until plans.length()).map(plans::getJSONObject).first { it.getInt("id") == selected }
            body.put("plan_id", selected).put("plan_fingerprint", plan.getString("fingerprint")).put("daily_limit", daily.toInt())
        }
        model.submit("trial-setup/app", body, "trial-setup")
    }, enabled = !busy && valid) { Text("Save trial settings") }
    TextButton(onClick = { model.open("trial-setup") }, enabled = !busy) { Text("Reload settings") }
}

@Composable
internal fun BrandSettings(data: JSONObject, busy: Boolean, model: HubModel) {
    val brand = data.optJSONObject("brand")
    val version = brand?.optInt("version", 0) ?: 0
    var name by rememberSaveable(version) { mutableStateOf(brand?.optString("name") ?: "") }
    var loading by rememberSaveable(version) { mutableStateOf(brand?.optString("loading_asset") ?: "") }
    var logo by rememberSaveable(version) { mutableStateOf(brand?.optString("logo_asset") ?: "") }
    var icon by rememberSaveable(version) { mutableStateOf(brand?.optString("icon_asset") ?: "") }
    var target by rememberSaveable { mutableStateOf("icon") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.uploadBrand(uri) { id -> when (target) { "loading" -> loading = id; "logo" -> logo = id; else -> icon = id } }
    }
    Text("App branding", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(name, { name = it.take(60) }, label = { Text("App name") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("PNG, JPEG or WebP · up to 1 MB")
    listOf(Triple("loading", "Loading image", loading), Triple("logo", "In-app logo / TV banner", logo), Triple("icon", "Launcher icon", icon)).forEach { (key, title, asset) ->
        OutlinedButton(onClick = { target = key; picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("$title${if (asset.isNotBlank()) " · selected" else " · choose image"}") }
    }
    val dirty = name != brand?.optString("name") || loading != brand?.optString("loading_asset") || logo != brand?.optString("logo_asset") || icon != brand?.optString("icon_asset")
    Button(onClick = { model.submit("app-brand", JSONObject().put("name", name).put("expected_version", version).put("loading_asset", loading).put("logo_asset", logo).put("icon_asset", icon), "app-brand") }, enabled = !busy && dirty && name.isNotBlank() && listOf(loading, logo, icon).all(String::isNotBlank) && (brand?.optBoolean("enabled") != false)) { Text("Save name & artwork") }
    if (brand != null) {
        Text("Package: ${brand.getString("package_name")}")
        Button(onClick = { model.submit("app-brand/builds", JSONObject().put("expected_version", version), "app-brand") }, enabled = !busy && !dirty && brand.optBoolean("enabled")) { Text("Build app") }
    }
    val builds = data.optJSONArray("builds") ?: JSONArray()
    for (index in 0 until builds.length()) {
        val build = builds.getJSONObject(index)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("${build.optString("app_version")} · ${build.optString("state").replace('_', ' ')}")
            build.optJSONObject("progress")?.let { Text(it.optString("label")) }
            Text(build.optString("created_at"))
            if (build.optString("state") in listOf("waiting_builder", "ready_to_publish", "build_failed")) TextButton(onClick = { model.submit("app-brand/builds/${build.getString("id")}/cancel", JSONObject(), "app-brand") }, enabled = !busy) { Text("Cancel unpublished build") }
        } }
    }
    TextButton(onClick = { model.open("app-brand") }, enabled = !busy) { Text("Refresh build status") }
}

@Composable
internal fun AddonSettings(data: JSONObject, busy: Boolean, model: HubModel) {
    var key by remember { mutableStateOf("") }
    val license = data.optJSONObject("licence")
    Text("Extra Playlists", style = MaterialTheme.typography.headlineSmall)
    Text("This is your separate monthly Extra Playlists add-on, not your My Hub app license.")
    Text("Status: ${license?.optString("effective_status") ?: "Not activated"}")
    license?.takeUnless { it.isNull("expires_at") }?.let { Text("Expires: ${it.getString("expires_at")} UTC") }
    OutlinedTextField(key, { key = it.take(80) }, label = { Text("Extra Playlists key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
    Button(onClick = { model.submit("addons/redeem", JSONObject().put("key", key.trim()), "addons"); key = "" }, enabled = !busy && key.isNotBlank()) { Text("Activate add-on") }
}
