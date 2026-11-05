package com.extreme.myhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

internal object RenewalForms {
    fun price(pkg: JSONObject, old: JSONObject?, country: String, amount: String, currency: String, enabled: Boolean) = JSONObject()
        .put("id", old?.text("id") ?: "").put("expected_revision", old?.optInt("revision") ?: 0)
        .put("package_id", pkg.getInt("id")).put("country", country).put("amount", amount).put("currency", currency).put("enabled", enabled)
    fun routing(country: String, revision: Int, choices: List<String>): JSONObject {
        require(country in listOf("US", "CA"))
        val selected = if (choices.firstOrNull() == "none") emptyList() else choices.filter { it != "none" }.distinct()
        require(selected.all { it in if (country == "CA") listOf("stripe", "square") else listOf("stripe", "square", "rampex") })
        return JSONObject().put("country", country).put("expected_revision", revision).put("gateways", JSONArray(selected))
    }
    fun money(price: JSONObject): String = BigDecimal(price.optLong("amount_minor")).movePointLeft(price.optInt("currency_exponent", 2)).toPlainString()
}

@Composable internal fun Renewals(state: HubState, model: HubModel) {
    val data = state.data!!; val route = "renewals/settings"
    var tab by remember { mutableStateOf("payments") }
    var form by remember { mutableStateOf<Form?>(null) }
    var query by remember { mutableStateOf("") }
    var paymentFilter by remember { mutableStateOf("all") }
    var selectedPackage by remember { mutableStateOf<JSONObject?>(null) }
    Text("CUSTOMER BILLING", style = MaterialTheme.typography.labelSmall, color = HubColors.muted)
    Text("Renewals", style = MaterialTheme.typography.headlineMedium)
    Text("Payments and plans, in one place.", color = HubColors.muted)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pairs("payments", "pricing", "gateways", "routing").forEach { (key, caption) -> FilterChip(selected = tab == key, onClick = { tab = key; query = "" }, label = { Text(caption) }) }
    }
    val policy = data.optJSONObject("policy")
    StatusPill(if (policy?.optBoolean("enabled") == true) "Payments enabled${if (policy.optBoolean("testing_only")) " · testing apps only" else ""}" else "New payments paused by owner", if (policy?.optBoolean("enabled") == true) HubColors.mint else HubColors.amber)
    val catalogueOnly = data.optBoolean("catalogue_only")
    if (catalogueOnly) Text("Catalogue-only connection. Configure prices; payment writes are disabled.")
    val prices = data.rows("prices")
    val catalog = data.optJSONObject("_catalog")
    when (tab) {
        "payments" -> {
            data.text("_orders_error").takeIf { it.isNotBlank() }?.let { MessageBanner(it, true) }
            OutlinedTextField(query, { query = it }, label = { Text("Search order reference") }, leadingIcon = { HubIcon("search") }, modifier = Modifier.fillMaxWidth())
            Choice("Payment state", paymentFilter, pairs("all", "attention", "paid", "awaiting", "fulfilled")) { paymentFilter = it }
            val orders = data.optJSONObject("_orders")?.rows() ?: emptyList()
            val filtered = orders.filter { o -> o.text("id").contains(query, true) && when (paymentFilter) {
                "attention" -> (o.text("payment_state") == "paid" && o.text("fulfilment_state") != "fulfilled") || o.text("payment_state") in listOf("review", "verification_pending", "link_uncertain")
                "fulfilled" -> o.text("fulfilment_state") == "fulfilled"
                "all" -> true
                else -> o.text("payment_state") == paymentFilter
            } }
            if (filtered.isEmpty()) EmptyState("No matching payments", "Recent orders will appear here.", "wallet")
            filtered.forEach { o -> DetailCard(o.text("id")) {
                Text("${RenewalForms.money(o)} ${o.text("currency")}", style = MaterialTheme.typography.headlineSmall)
                StatusPill(title(o.text("payment_state")), if (o.text("payment_state") == "paid") HubColors.mint else HubColors.amber)
                Details(o, "fulfilment_state", "mode", "created_at")
                if (o.text("mode") in listOf("mock", "sandbox")) Text("Test only · no real account renewal")
                if (o.text("payment_state") in listOf("paid", "review", "verification_pending") && o.text("fulfilment_state") != "fulfilled" && !o.flag("checkout_superseded"))
                    ConfirmAction("Recheck original order", state.busy, "Reconcile this payment and renewal using the original order. No new charge is created.") { model.submit("renewals/orders/${o.getString("id")}/retry", JSONObject(), route) }
                if (o.text("mode") == "mock" && data.optJSONObject("_orders")?.optBoolean("simulation_allowed") == true) Action("Simulate test payment", state.busy) { model.submit("renewals/orders/${o.getString("id")}/simulate", JSONObject(), route) }
            } }
            Text("Up to 50 recent orders, matching the Hub website.")
        }
        "pricing" -> {
            Text(data.text("_catalog_error"))
            val packages = catalog?.rows("packages") ?: emptyList()
            val conversion = data.optJSONObject("trial_conversion") ?: JSONObject()
            DetailCard("Trial conversion") {
                Text(if (conversion.optBoolean("enabled")) "Enabled · ${packages.find { it.optInt("id") == conversion.optInt("package_id") }?.text("name") ?: "Plan no longer offered"}" else "Disabled")
                Text("Expired trials use this paid plan. Paid subscriptions retain their existing package.")
                if (catalog != null && !catalogueOnly) Action("Edit conversion plan", state.busy) {
                    form = Form("Trial conversion", listOf(Field("enabled", value = if (conversion.optBoolean("enabled")) "yes" else "no", choices = pairs("yes", "no")), Field("package", value = conversion.text("package_id"), choices = listOf("" to "No plan") + packages.map { it.getInt("id").toString() to it.getString("name") }, required = false))) { v ->
                        model.submit("renewals/trial-conversion", JSONObject().put("enabled", v["enabled"] == "yes").put("package_id", v["package"]?.toIntOrNull() ?: JSONObject.NULL).put("expected_revision", conversion.optInt("revision")), route)
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search offered packages") }, modifier = Modifier.fillMaxWidth())
            packages.filter { it.getString("name").contains(query, true) }.forEach { pkg -> DetailCard(pkg.getString("name")) {
                Details(pkg, "duration", "period", "max_connections", "credits", "funded")
                prices.filter { it.optInt("package_id") == pkg.getInt("id") }.forEach { p -> Text("${p.text("country", "Default").ifBlank { "Default" }}: ${RenewalForms.money(p)} ${p.text("currency")} · ${if (p.optBoolean("enabled")) "on" else "off"}") }
                Action("Set country price", state.busy) { selectedPackage = pkg }
            } }
        }
        "gateways" -> {
            val connections = data.rows("connections").ifEmpty { listOfNotNull(data.optJSONObject("connection")) }
            listOf("stripe", "square", "rampex").forEach { gateway ->
                val c = connections.find { it.text("gateway", "rampex") == gateway }
                DetailCard(title(gateway)) {
                    if (c != null) { Details(c, "business_name", "merchant_id", "mode", "webhook_url", "webhook_verified_at"); Text(if (c.optBoolean("checkout_enabled", true)) "Enabled" else "Disabled") }
                    else Text("Not connected")
                    Text(when (gateway) { "stripe" -> "Subscribe to checkout.session.completed, checkout.session.async_payment_succeeded and checkout.session.expired."; "square" -> "Use a card-enabled Location ID. Subscribe to payment.created and payment.updated."; else -> "Configure your payout wallet and webhook secret. Rampex is US-only." })
                    if (!catalogueOnly) {
                        if (c != null) ConfirmAction(if (c.optBoolean("checkout_enabled", true)) "Disable new checkouts" else "Enable new checkouts", state.busy) {
                            model.submit("renewals/gateway-availability", JSONObject().put("gateway", gateway).put("connection_id", c.getString("id")).put("expected_revision", c.optInt("availability_revision")).put("enabled", !c.optBoolean("checkout_enabled", true)), route)
                        }
                        Action(if (c == null) "Connect merchant" else "Replace connection", state.busy) {
                            model.prepare("renewals/connection-setup", JSONObject().put("gateway", gateway)) { setup ->
                                form = Form("${title(gateway)} · configure webhook first: ${setup.getString("webhook_url")}", listOf(
                                    Field("mode", value = if (gateway == "rampex") "live" else "sandbox", choices = if (gateway == "rampex") pairs("live") else pairs("sandbox", "live")),
                                    Field("api_key", if (gateway == "square") "Square access token" else "API key", secret = true, max = 512)
                                ) + (if (gateway == "square") listOf(Field("location_id", max = 180)) else emptyList()) + Field("webhook_secret", secret = true, max = 512)) { v ->
                                    model.submit("renewals/connection", JSONObject(v).put("gateway", gateway).put("setup_id", setup.getString("setup_id")), route)
                                }
                            }
                        }
                    }
                }
            }
        }
        "routing" -> for (country in listOf("US", "CA")) {
            val rule = data.optJSONObject("routing")?.optJSONObject(country) ?: JSONObject()
            val gateways = rule.optJSONArray("gateways") ?: JSONArray()
            DetailCard(if (country == "CA") "Canada" else "United States") {
                Text((0 until gateways.length()).joinToString(" → ") { title(gateways.getString(it)) }.ifEmpty { "Disabled" })
                if (!catalogueOnly) Action("Edit routing", state.busy) {
                    val options = pairs("none", "stripe", "square") + if (country == "US") pairs("rampex") else emptyList()
                    form = Form("Routing · $country", (0..2).map { i -> Field("choice$i", listOf("Preferred gateway", "First alternative", "Second alternative")[i], gateways.optString(i, "none"), options) }) { v ->
                        model.submit("renewals/routing", RenewalForms.routing(country, rule.optInt("revision"), (0..2).map { v.getValue("choice$it") }), route)
                    }
                }
            }
        }
    }
    Action("Refresh renewals", state.busy) { model.open(route) }
    NativeForm(form) { form = null }
    selectedPackage?.let { pkg -> PriceDialog(pkg, prices, data.optJSONObject("currency_exponents") ?: JSONObject().put("USD", 2).put("CAD", 2), { selectedPackage = null }) { body -> selectedPackage = null; model.submit("renewals/prices", body, route) } }
}

@Composable private fun PriceDialog(pkg: JSONObject, prices: List<JSONObject>, exponents: JSONObject, close: () -> Unit, save: (JSONObject) -> Unit) {
    var country by remember { mutableStateOf("") }
    val old = prices.find { it.optInt("package_id") == pkg.getInt("id") && it.text("country") == country }
    var amount by remember(country) { mutableStateOf(old?.let(RenewalForms::money) ?: "") }
    var currency by remember(country) { mutableStateOf(old?.text("currency") ?: if (country == "CA") "CAD" else "USD") }
    var enabled by remember(country) { mutableStateOf(old?.optBoolean("enabled") ?: true) }
    AlertDialog(onDismissRequest = close, title = { Text("${pkg.getString("name")} pricing") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Country · changing resets this unsaved form", country, listOf("" to "Default", "US" to "United States", "CA" to "Canada")) { country = it }
            OutlinedTextField(amount, { amount = it.take(16) }, label = { Text("Customer price") })
            Choice("Currency", currency, exponents.keys().asSequence().map { it to it }.toList()) { currency = it }
            Row { Checkbox(enabled, { enabled = it }); Text("Available for checkout", Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { save(RenewalForms.price(pkg, old, country, amount, currency, enabled)) }, enabled = amount.matches(Regex("[0-9]+(\\.[0-9]+)?")) && (amount.toBigDecimalOrNull()?.scale() ?: Int.MAX_VALUE) <= exponents.optInt(currency, 2)) { Text("Save price") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
