package com.extreme.myhub

import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject

@Composable internal fun AccountControls(state: HubState, model: HubModel) {
    val route = state.route; val data = state.data!!
    var form by remember { mutableStateOf<Form?>(null) }
    Text(title(route.substringBefore('/')), style = MaterialTheme.typography.headlineSmall)
    when {
        route == "account" -> Text("You sign in using your reseller-panel credentials. Manage your password through the reseller website.")
        route == "trial-contacts" || route == "recovery-holds" -> {
            data.rows().forEach { row -> DetailCard(row.text("first_name", row.text("label", "Contact"))) {
                Details(row, "state", "phone_masked", "created_at", "expires_at", "model", "recovery_state", "line_username")
                Action("Review", state.busy) { model.open("$route/${row.getString("id")}") }
            } }
            Pager(state, model)
            if (route == "recovery-holds") {
                data.rows("restricted_devices").forEach { row -> DetailCard(row.text("label")) { Details(row, "model", "policy"); Action("Open device", state.busy) { model.open("devices/${row.getString("id")}") } } }
                val p = data.optJSONObject("restricted_pagination") ?: JSONObject()
                if (p.optInt("page", 1) > 1) Action("Previous restricted devices", state.busy) { model.open(route, state.page, filters = mapOf("restricted_page" to (p.getInt("page") - 1).toString())) }
                if (p.optInt("page", 1) < p.optInt("pages", 1)) Action("Next restricted devices", state.busy) { model.open(route, state.page, filters = mapOf("restricted_page" to (p.getInt("page") + 1).toString())) }
            }
        }
        route.startsWith("trial-contacts/") -> {
            ResultDetails(data)
            Action("Refresh panel status", state.busy) { model.submit("$route/refresh", JSONObject(), route) }
            ConfirmAction("Remove contact details", state.busy, "Remove stored contact details. Panel notes and trial eligibility are not changed.") { model.submit("$route/redact", JSONObject().put("confirmed", true), route) }
        }
        else -> {
            Details(data, "model", "installation_id")
            data.rows("devices").forEach { row -> DetailCard(row.text("label")) {
                Details(row, "model", "policy", "last_seen_at", "hint_matches")
                if (row.text("policy") == "active") ConfirmAction("Allow recovery onto this device", state.busy, "Bind the fresh signed recovery request to this existing device?") { model.submit(route, JSONObject().put("action", "allow").put("device_id", row.getString("id")).put("expected_revision", data.getString("expected_revision")).put("confirm", true), "recovery-holds") }
            } }
            ConfirmAction("Reject recovery", state.busy) { model.submit(route, JSONObject().put("action", "reject").put("expected_revision", data.getString("expected_revision")).put("confirm", true), "recovery-holds") }
        }
    }
    Action("Refresh", state.busy) { model.open(route, state.page, state.search, state.filters) }
    NativeForm(form) { form = null }
}
