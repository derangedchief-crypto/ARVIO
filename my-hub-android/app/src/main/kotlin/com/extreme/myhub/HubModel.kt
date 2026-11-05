package com.extreme.myhub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject

internal data class HubState(
    val session: Session? = null, val license: JSONObject? = null, val busy: Boolean = true,
    val error: String? = null, val notice: String? = null, val route: String = "overview",
    val data: JSONObject? = null, val page: Int = 1, val search: String = "",
    val filters: Map<String, String> = emptyMap(), val pending: Mutation? = null, val canBack: Boolean = false,
    val inspection: JSONObject? = null, val syncError: String? = null
)

internal class HubModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private val client = HubClient()
    private val mutable = MutableStateFlow(HubState())
    val state = mutable.asStateFlow()
    private val history = mutableListOf<Triple<String, Int, Pair<String, Map<String, String>>>>()
    private var operationEpoch = 0L
    private var editingDevice = false
    private var inspectionScope: JSONObject? = null
    fun holdDeviceUpdates(hold: Boolean) { editingDevice = hold }
    fun inspectScope(profile: String, source: String) {
        operationEpoch++
        inspectionScope = JSONObject().put("profile_ref",profile).put("source_ref",source)
        mutable.value = mutable.value.copy(data=null,inspection=null)
    }

    /** Called only from a STARTED lifecycle. Observations do not block navigation. */
    suspend fun observeVisible() {
        var revision: String? = null
        val initial = mutable.value
        val id = Regex("^devices/([a-f0-9]{32})(?:/.*)?$").find(initial.route)?.groupValues?.get(1) ?: return
        while (currentCoroutineContext().isActive) {
            val before = mutable.value
            if (before.route != initial.route || before.session != initial.session) return
            if (!before.busy && before.pending == null) {
                val epoch = operationEpoch
                try {
                    val section = when (before.route.substringAfter("devices/$id", "").trim('/').substringBefore('/')) {
                        "playback" -> "playback"; "preferences" -> "preferences"
                        "sources" -> "sources"; "category-layout", "categories" -> "channels"
                        "support", "diagnostics" -> "support"; "commands" -> "activity"
                        "favourites" -> "favourites"; else -> "overview"
                    }
                    val result = client.request("manage/devices/$id/inspection", before.session,
                        JSONObject().put("section",section).put("scope",inspectionScope ?: JSONObject()))
                    if (operationEpoch == epoch && mutable.value.route == before.route && mutable.value.session == before.session) {
                        mutable.value = mutable.value.copy(inspection=result,syncError=null)
                        val sections = result.getJSONObject("sections")
                        val next = result.optString("revision") + sections.keys().asSequence().sorted().joinToString { key ->
                            key + ":" + sections.getJSONObject(key).optString("snapshot_id")
                        }
                        // Snapshot editors are pinned to their original revision. Never reload them.
                        val scopedLayoutReady = !before.route.endsWith("/category-layout") || inspectionScope == null ||
                            sections.optJSONObject("get_category_layout")?.isNull("snapshot_id") == false
                        if (next != revision && scopedLayoutReady && !editingDevice && before.route.split('/').size <= 3) {
                            load(before.route,before.page,before.search,before.filters,quiet=true)
                            if (operationEpoch == epoch && !editingDevice) revision=next
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: HubFailure) {
                    if (operationEpoch == epoch) {
                        if (e.status == 401) { store.clear();history.clear();mutable.value=HubState(busy=false);return }
                        if (e.status in listOf(403,404)) { mutable.value=mutable.value.copy(data=null,inspection=null,syncError="Device access changed. Reopen the workspace.");return }
                        mutable.value=mutable.value.copy(syncError="Connection interrupted. Reconnecting automatically.")
                    }
                } catch (_: Exception) {
                    if (operationEpoch == epoch) mutable.value=mutable.value.copy(syncError="Connection interrupted. Last-known data shown.")
                }
            }
            delay(5_000)
        }
    }
    init {
        val session = store.load()
        val pending = try { store.pending()?.let(Mutation::decode) } catch (_: Exception) { Mutation("unknown", "overview", null, null) }
        mutable.value = HubState(session = session, busy = false, pending = pending)
        if (session != null) refreshLicense()
    }
    private fun operation(work: suspend () -> Unit) {
        if (mutable.value.busy) return
        operationEpoch++
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        viewModelScope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: HubFailure) {
                if (e.status == 401) { store.clear(); history.clear(); mutable.value = HubState(busy = true) }
                if (e.status == 403) {
                    val license = runCatching { client.request("license", mutable.value.session) }.getOrNull()
                    mutable.value = mutable.value.copy(data = null, license = license)
                }
                mutable.value = mutable.value.copy(error = e.message)
            } catch (_: Exception) { mutable.value = mutable.value.copy(error = "Could not complete the request. Check your connection and retry.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun login(username: String, password: String) = operation {
        val session = client.login(username, password)
        store.save(session)
        mutable.value = HubState(session = session, busy = true)
        checkLicense()
    }
    private suspend fun checkLicense() {
        val license = client.request("license", mutable.value.session)
        mutable.value = mutable.value.copy(license = license, data = null)
        if (license.getBoolean("allowed")) load("overview")
    }
    fun refreshLicense() = operation { checkLicense() }
    fun activate(key: String) = operation {
        val result = client.request("activate", mutable.value.session, JSONObject().put("key", key.trim()))
        mutable.value = mutable.value.copy(license = result)
        if (result.getBoolean("allowed")) load("overview")
    }
    fun logout() = operation {
        val session = mutable.value.session
        // Retain the token if server revocation fails so the user can retry.
        client.request("logout", session, JSONObject())
        store.clear(); history.clear(); mutable.value = HubState(busy = true)
    }
    fun open(route: String, page: Int = 1, search: String = "", filters: Map<String, String> = emptyMap()) = operation {
        val previous = mutable.value
        if (previous.route != route) history.add(Triple(previous.route, previous.page, previous.search to previous.filters))
        load(route, page, search, filters)
    }
    fun back() = operation { if (history.isNotEmpty()) { val prior = history.removeAt(history.lastIndex); load(prior.first, prior.second, prior.third.first, prior.third.second) } }
    fun navigate(route: String) = operation { history.clear(); load(route) }
    private suspend fun load(route: String, page: Int = 1, search: String = "", filters: Map<String, String> = emptyMap(), quiet: Boolean = false) {
        val epoch=operationEpoch;val session=mutable.value.session
        // Older detail endpoints omit the verified username already returned by
        // the authenticated customer list. Keep that identity when opening a row.
        val customerId = Regex("^customers/([a-f0-9]{32})$").find(route)?.groupValues?.get(1)
        val previousData = mutable.value.data
        val selectedCustomer = if (customerId == null) null else
            previousData?.takeIf { it.optString("id") == customerId }
                ?: previousData?.rows()?.find { it.optString("id") == customerId }
        if (!quiet) {
            if (mutable.value.route.split('/').take(2) != route.split('/').take(2)) inspectionScope=null
            mutable.value = mutable.value.copy(route = route, data = if (mutable.value.route == route) mutable.value.data else null, page = page, search = search, filters = filters, canBack = history.isNotEmpty(),inspection=if(mutable.value.route==route)mutable.value.inspection else null,syncError=null)
        }
        val query = when (route) {
            "customers", "devices", "device-profiles", "preference-profiles", "settings-presets", "category-layout-templates" -> mapOf("page" to page.toString(), "search" to search)
            "audit", "recovery-holds", "trial-contacts", "device-setups" -> mapOf("page" to page.toString())
            else -> if (route.endsWith("/commands") || route.endsWith("/favourites")) mapOf("page" to page.toString()) else emptyMap()
        } + filters + if (route.endsWith("/category-layout") && inspectionScope != null) {
            mutable.value.inspection?.optJSONObject("sections")?.optJSONObject("get_category_layout")
                ?.optString("snapshot_id")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                ?.let { mapOf("command_id" to it) } ?: emptyMap()
        } else emptyMap()
        val data = when {
            route in listOf("library", "account", "more") -> JSONObject()
            route.endsWith("/playback") || route.endsWith("/categories") -> client.request("manage/${route.substringBeforeLast('/')}/commands", mutable.value.session)
            route.endsWith("/setup") && route.startsWith("devices/") -> client.request("manage/${route.substringBeforeLast('/')}", mutable.value.session).put("_profiles", allItems("device-profiles"))
            else -> client.request("manage/$route", mutable.value.session, query = query)
        }
        if (customerId != null && data.isNull("line_username") && selectedCustomer?.isNull("line_username") == false)
            data.put("line_username", selectedCustomer.getString("line_username"))
        if (route == "renewals/settings") {
            for ((key, path) in listOf("_catalog" to "renewals/catalog", "_orders" to "renewals/orders")) {
                try { data.put(key, client.request("manage/$path", mutable.value.session)) }
                catch (e: HubFailure) { if (e.status in listOf(401,403)) throw e; data.put(key + "_error", e.message) }
                catch (_: java.io.IOException) { data.put(key + "_error", "Could not load. Refresh to retry.") }
            }
        }
        if (route == "device-profiles" || route.startsWith("device-profiles/")) {
            data.put("_layouts", allItems("category-layout-templates")).put("_preferences", allItems("preference-profiles"))
        }
        if (route == "device-defaults") data.put("_profiles", allItems("device-profiles"))
        if (route.matches(Regex("devices/[a-f0-9]{32}/preferences"))) data.put("_profiles", allItems("preference-profiles"))
        if (route.matches(Regex("devices/[a-f0-9]{32}/playback"))) data.put("_presets", allItems("settings-presets"))
        if (route.matches(Regex("devices/[a-f0-9]{32}/category-layout"))) data.put("_templates", allItems("category-layout-templates"))
        if (route.matches(Regex("devices/[a-f0-9]{32}/[^/]+"))) data.put("_device", client.request("manage/${route.substringBeforeLast('/')}", mutable.value.session))
        if (route.matches(Regex("devices/[a-f0-9]{32}/favourites/backups/[a-f0-9]{32}"))) data.put("_preview", client.request("manage/$route/preview", mutable.value.session))
        if (operationEpoch==epoch && mutable.value.route==route && mutable.value.session==session && (!quiet || !editingDevice)) mutable.value = mutable.value.copy(data = data)
    }
    private suspend fun allItems(route: String): org.json.JSONArray {
        val rows = org.json.JSONArray(); var page = 1
        do { val reply = client.request("manage/$route", mutable.value.session, query = mapOf("page" to page.toString())); reply.rows().forEach(rows::put); val pages = reply.optInt("pages", 1); page++ } while (page <= pages)
        return rows
    }
    fun read(route: String, done: (JSONObject) -> Unit) = operation { done(client.request("manage/$route", mutable.value.session)) }
    fun prepare(route: String, body: JSONObject, done: (JSONObject) -> Unit) = operation { done(client.request("manage/$route", mutable.value.session, body)) }
    fun layoutPreview(template: String, catalog: JSONObject, done: (JSONObject) -> Unit) = operation {
        val saved = client.request("manage/category-layout-templates/$template", mutable.value.session)
        try { done(LayoutForms.template(catalog, saved.rows("entries"))) }
        catch (e: IllegalArgumentException) { throw HubFailure(409, e.message ?: "Layout could not be matched.") }
    }
    private fun pending(value: Mutation?) { store.savePending(value?.encode()); mutable.value = mutable.value.copy(pending = value) }
    private suspend fun write(route: String, fields: JSONObject, refresh: String): JSONObject {
        if (mutable.value.pending != null) throw HubFailure(409,"Review the uncertain previous operation before sending another change.")
        val mutation = Mutation.create(route, refresh, fields); pending(mutation)
        return try { client.request("manage/$route", mutable.value.session, fields, idempotency = mutation.key).also { pending(null) } }
        catch (e: HubFailure) { if (e.status in listOf(400,401,403,404,409,413,415,422,429)) pending(null); throw e }
    }
    fun retryPending() = operation {
        val p = mutable.value.pending ?: return@operation
        if (p.key == null || p.body == null) throw HubFailure(409,"Reload and verify the result before acknowledging this operation. It cannot be replayed automatically.")
        client.request("manage/${p.route}", mutable.value.session, JSONObject(p.body), idempotency = p.key)
        pending(null); load(p.refresh)
    }
    fun acknowledgePending() { if (!mutable.value.busy) pending(null) }
    fun save(route: String, fields: JSONObject) = operation {
        val result = write(route, fields, route)
        load(if (route == "customers" || route == "devices") "$route/${result.getString("id")}" else route)
        mutable.value = mutable.value.copy(notice = "Saved.")
    }
    fun submit(route: String, fields: JSONObject, refresh: String) = operation {
        write(route, fields, refresh)
        load(refresh)
        mutable.value = mutable.value.copy(notice = "Saved.")
    }
    fun uploadBrand(uri: android.net.Uri, done: (String) -> Unit) = operation {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 1024 * 1024) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 1024 * 1024 + 1 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
                ?: throw IllegalArgumentException("Cannot read image")
        }
        if (bytes.size > 1024 * 1024) throw HubFailure(422, "Choose a PNG, JPEG or WebP image up to 1 MB.")
        val result = client.request("manage/app-brand/assets", mutable.value.session, JSONObject().put("image", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
        done(result.getString("id"))
    }
    fun deviceCommand(device: String, kind: String, payload: JSONObject = JSONObject()) = operation {
        val previous = mutable.value
        val result = write("devices/$device/commands", JSONObject().put("kind", kind).put("payload", payload), previous.route)
        load(previous.route, previous.page, previous.search, previous.filters)
        mutable.value = mutable.value.copy(notice = "Request sent. Progress and the confirmed result update automatically.")
    }
    fun password(current: String, next: String) = operation {
        write("password", JSONObject().put("current_password", current).put("new_password", next), "account")
        store.clear(); history.clear(); mutable.value = HubState(busy = true, notice = "Password changed. Sign in again.")
    }
    fun download(route: String, uri: android.net.Uri, sha256: String? = null) = operation {
        val session = mutable.value.session ?: return@operation
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val temporary = java.io.File.createTempFile("download-", ".part", app.cacheDir)
            try {
                temporary.outputStream().use { client.download(route, session, it, sha256) }
                app.contentResolver.openOutputStream(uri, "wt")?.use { output -> temporary.inputStream().use { it.copyTo(output) } } ?: throw java.io.IOException("Could not open destination.")
            } finally { temporary.delete() }
        }
        mutable.value = mutable.value.copy(notice = "Download saved.")
    }
}
