package com.arflix.tv.management

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import com.arflix.tv.BuildConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.validManagedSourceConfig
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.Gson
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Foreground-only management. Enrollment/outages never change IPTV auth, update
 * source, profiles or playback. Only an authenticated explicit policy may gate UI. */
@Singleton
class DeviceManagementAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: Lazy<IptvRepository>,
    private val profiles: ProfileManager,
) {
    private val vault by lazy { ManagementVault(context) }
    private val api = ManagementApi()
    private val mutation = Mutex()
    private val runner = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DeviceManagementState())
    val state = _state.asStateFlow()
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    private class Active(val account: ManagementAccount, @Volatile var session: ManagementSession, val publicKey: ByteArray) {
        var status = "Connecting to control center"
        var heartbeat = 60
        var nextRenew = 0L
        var recoveryHintsSupported = false
        var favouritesStatus = "Waiting for managed check-in"
    }
    @Volatile private var active: Active? = null
    private val recoveryHint by lazy { DeviceRecoveryHint.read(context) }

    fun onInteraction() { lastInteraction = SystemClock.elapsedRealtime() }
    fun checkInNow() {
        val current=active ?: return
        scope.launch {
            try { change(current) { it.copy(unmanagedUntilMs = 0) }; wake.trySend(Unit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.status="Management retry state could not be saved";publish(current) }
        }
    }
    private suspend fun waitForNextCheckIn(milliseconds: Long) { withTimeoutOrNull(milliseconds) { wake.receive() } }
    fun dismissMessage(id: String) {
        val current = active ?: return
        scope.launch {
            mutation.withLock {
                if (active !== current || current.session.message?.id != id) return@withLock
                val next = current.session.copy(message = null)
                current.session = next
                publish(current)
                // A storage failure must not trap the user in a provider dialog.
                try { vault.save(next) } catch (_: Exception) { /* retain in-memory dismissal */ }
            }
        }
    }
    private suspend fun change(current: Active, update: (ManagementSession) -> ManagementSession) = mutation.withLock {
        if (active !== current) return@withLock
        val proposed = update(current.session)
        val now = System.currentTimeMillis()
        val next = proposed.copy(message = proposed.message?.takeIf { it.expiresAtMs - now in 1..3600_000L })
        try { vault.save(next) }
        catch (error: Exception) {
            // A full/unwritable disk must not ignore a newly authenticated block.
            if (next.policy in setOf("suspended", "blocked", "removed")) { current.session = next; publish(current) }
            throw error
        }
        current.session = next
        publish(current)
    }
    private fun publish(current: Active) {
        if (active !== current) return
        _state.value = DeviceManagementState(false, current.session.installationId, current.status, current.session.policy,
            current.session.message?.takeIf { it.expiresAtMs - System.currentTimeMillis() in 1..3600_000L },current.favouritesStatus,current.session.recoveryPending)
    }
    suspend fun runWhileStarted(): Unit = withContext(Dispatchers.IO) {
        runner.withLock {
        repository.get().observeActivationConfig().map(::managementAccount).distinctUntilChanged()
            .retryWhen { error, _ ->
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(checkingLocalState = false, status = "Management state unavailable")
                delay(15_000); true
            }.collectLatest { account ->
                if (account == null) {
                    ManagementEvents.unbind()
                    mutation.withLock { active = null; _state.value = DeviceManagementState(false, status = "No eligible activation account") }
                    return@collectLatest
                }
                val knownPolicy = mutation.withLock {
                    val sameAccount = active?.account?.scope == account.scope
                    val previous = _state.value
                    active = null
                    ManagementEvents.unbind()
                    _state.value = preparingManagementAccount(previous, sameAccount)
                    if (sameAccount) previous.policy else null
                }
                try {
                    val key = vault.publicKey(account.scope)
                    val fingerprint = managementHash(key)
                    var saved = vault.load(account.scope) ?: ManagementSession(account.scope, newInstallation())
                    if(saved.catalogSecret?.matches(Regex("[a-f0-9]{64}"))!=true)saved=saved.copy(catalogSecret=managementHash(UUID.randomUUID().toString()+UUID.randomUUID().toString()))
                    if (knownPolicy in setOf("suspended", "blocked", "removed")) saved = saved.copy(policy = knownPolicy)
                    if (saved.keyFingerprint.isNotEmpty() && saved.keyFingerprint != fingerprint) {
                        // A lost signing key cannot silently reuse another identity.
                        // Keep known restrictions until another owner-approved enrollment.
                        saved = saved.copy(installationId = newInstallation(), credential = null, deviceId = null, requestId = null, pollToken = null, receipts = emptyList(), message = null)
                    }
                    saved = saved.copy(keyFingerprint = fingerprint)
                    vault.save(saved)
                    val current = Active(account, saved, key)
                    mutation.withLock { active = current; publish(current) }
                    var failures = 0
                    while (currentCoroutineContext().isActive) {
                        try {
                            cycle(current)
                            failures = 0
                            waitForNextCheckIn((current.heartbeat.coerceIn(15, 300) * 1000L) + Random.nextLong(0, 5000))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failures = (failures + 1).coerceAtMost(5)
                            if (error is ManagementHttpException && error.status == 401 && current.session.credential != null) {
                                change(current) { it.copy(credential = null, credentialExpiresAtMs = 0) }
                            }
                            current.status = if (current.session.policy in setOf("suspended", "blocked", "removed")) "Waiting for provider policy check" else "Control center unavailable — app activation is unchanged"
                            publish(current)
                            waitForNextCheckIn((10_000L shl (failures - 1)).coerceAtMost(300_000) + Random.nextLong(0, 3000))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // No plaintext fallback and no clearing customer/profile stores.
                    _state.value = _state.value.copy(checkingLocalState = false, status = "Management key/state unavailable")
                }
            }
        }
    }
    private suspend fun enroll(current: Active) {
        val session = current.session
        val challenge = api.control("/api/device/enrollment/challenge", JSONObject().put("schema_version", 1)
            .put("installation_id", session.installationId).put("key_fingerprint", session.keyFingerprint).put("app_package", context.packageName))
        require(challenge.getInt("schema_version") == 1)
        val nonce = challenge.getString("challenge").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val proof = api.proof(current.account, session.installationId, session.keyFingerprint, context.packageName, nonce)
        val hint=if(challenge.optInt("device_recovery_version")==1)recoveryHint else null
        val signature = vault.sign(current.account.scope, enrollmentSigningText(nonce, proof, session.installationId, context.packageName, session.keyFingerprint,hint))
        val result = api.control("/api/device/enrollment/register", JSONObject().put("schema_version", 1).put("challenge", nonce)
            .put("proof", proof).put("public_key", Base64.encodeToString(current.publicKey, Base64.NO_WRAP)).put("signature", signature)
            .put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(120)).put("model", Build.MODEL.take(120))
             .put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").put("app_version", BuildConfig.VERSION_NAME)
             .apply { if(hint!=null)put("recovery_hint",hint) })
        require(result.getInt("schema_version") == 1 && result.getString("installation_id") == session.installationId)
        recoveryPolicy(current,result)
        if (result.optString("status") == "not_managed") {
            change(current) { it.copy(requestId = null, pollToken = null, unmanagedUntilMs = System.currentTimeMillis() + result.optInt("retry_after_seconds", 3600).coerceIn(300, 86400) * 1000L) }
            current.status = "Device management is not enabled for this reseller"
            current.heartbeat = 300
            publish(current)
            return
        }
        val id = result.getString("request_id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val token = result.getString("poll_token").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        change(current) { it.copy(requestId = id, pollToken = token, unmanagedUntilMs = 0) }
    }
    private fun delivery(session: ManagementSession) = JSONObject().put("schema_version", 1).put("request_id", session.requestId).put("poll_token", session.pollToken)
    private suspend fun recoveryPolicy(current:Active,result:JSONObject){
        val policy=result.optString("recovery_policy")
        if(policy in setOf("blocked","suspended","removed"))change(current){it.copy(policy=policy,
            recoveryPending=result.optString("status")=="pending"&&policy=="suspended"&&result.optString("recovery_state")!="restricted")}
    }
    private suspend fun poll(current: Active) {
        val session = current.session
        try {
            val result = api.control("/api/device/enrollment/poll", delivery(session))
            require(result.getInt("schema_version") == 1 && result.getString("request_id") == session.requestId && result.getString("installation_id") == session.installationId)
            recoveryPolicy(current,result)
            when (result.getString("status")) {
                "pending" -> {
                    if(result.optString("recovery_state").isNotBlank()){
                        current.status="Checking previous device identity"
                        current.heartbeat=300
                        if(result.optBoolean("recovery_retry"))change(current){it.copy(requestId=null,pollToken=null)}
                    }else { current.status = "Awaiting provider approval"; current.heartbeat = 60 }
                }
                "rejected" -> { current.status = "Enrollment declined — contact your provider"; current.heartbeat = 300 }
                "approved" -> {
                    val id = result.getString("device_id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
                    val token = result.getString("credential").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    val expiry = parseManagementTime(result.getString("credential_expires_at"))
                    change(current) { it.copy(deviceId = id, credential = token, credentialExpiresAtMs = expiry) }
                    confirm(current)
                }
                else -> error("Unknown enrollment status")
            }
        } catch (error: ManagementHttpException) {
            if (error.status == 401 || error.status == 409) change(current) { it.copy(requestId = null, pollToken = null) } else throw error
        }
        publish(current)
    }
    private suspend fun confirm(current: Active) {
        val session = current.session
        if (session.pollToken == null || session.requestId == null || session.credential == null) return
        try {
            val result = api.control("/api/device/enrollment/confirm", delivery(session))
            require(result.getInt("schema_version") == 1 && result.getBoolean("confirmed") && result.getString("device_id") == session.deviceId)
            change(current) { it.copy(requestId = null, pollToken = null) }
        } catch (error: ManagementHttpException) {
            if (error.status == 401 || error.status == 409) change(current) { it.copy(requestId = null, pollToken = null) } else throw error
        }
    }
    private suspend fun cycle(current: Active) {
        val session = current.session
        if (session.credential == null) {
            if (session.pollToken == null && session.unmanagedUntilMs - System.currentTimeMillis() in 1..86400_000L) {
                current.status = "Device management is not enabled for this reseller"; current.heartbeat = 300; publish(current); return
            }
            if (session.pollToken == null) enroll(current)
            if (current.session.pollToken == null) return
            poll(current)
            if (current.session.credential == null) return
        }
        confirm(current)
        // Renew in advance, but a temporarily unavailable proof worker must not
        // stop existing authenticated policy checks while the token is still valid.
        if (current.session.credentialExpiresAtMs > 0 && current.session.credentialExpiresAtMs - System.currentTimeMillis() < 7L * 86400_000 && SystemClock.elapsedRealtime() >= current.nextRenew) {
            current.nextRenew = SystemClock.elapsedRealtime() + 3600_000
            try { enroll(current); poll(current) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* retain usable credential */ }
        }
        val storage = StatFs(context.filesDir.absolutePath)
        val report = JSONObject().put("schema_version", 1).put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(120))
            .put("account_username",current.account.username)
            .put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})").put("app_version", BuildConfig.VERSION_NAME)
            .put("activity_state", if (SystemClock.elapsedRealtime() - lastInteraction >= 300_000) "idle" else "active")
            .put("capabilities", JSONArray(MANAGEMENT_CAPABILITIES)).put("storage_total", storage.totalBytes).put("storage_free", storage.availableBytes)
            .apply { val hint=recoveryHint;val id=current.session.deviceId
                if(current.recoveryHintsSupported&&hint!=null&&id!=null){
                    put("recovery_hint",hint).put("recovery_signature",vault.sign(current.account.scope,DeviceRecoveryHint.signingText(id,context.packageName,hint)))
                }
            }
        val response=optionalRecoveryCheckIn(report.has("recovery_hint")){includeHint->
            if(!includeHint){report.remove("recovery_hint");report.remove("recovery_signature")}
            api.control("/api/device/check-in",report,current.session.credential)
        }
        require(response.getInt("schema_version") == 1 && response.getString("device_id") == current.session.deviceId)
        current.recoveryHintsSupported=response.optInt("device_recovery_version")==1
        val policy = response.getString("policy").also { require(it in MANAGEMENT_POLICIES) }
        current.heartbeat = response.optInt("heartbeat_seconds", 60).coerceIn(15, 300)
        current.status = if (policy == "active") "Connected to provider control center" else "Provider policy: $policy"
        change(current) { it.copy(policy = policy, recoveryPending=false, message = if (policy == "active") it.message else null) }
        if (policy == "removed") {
            ManagementEvents.unbind()
            change(current) { it.copy(credential = null, requestId = null, pollToken = null) }
            return
        }
        if (policy != "active") { ManagementEvents.unbind();return }
        ManagementEvents.bind(context,current.account.scope)
        try { captureFavourites(current,"automatic",false) } catch(cancelled: CancellationException){throw cancelled} catch(_: Exception){current.favouritesStatus="Backup unavailable; existing server backups retained";publish(current)}
        val commands = response.getJSONArray("commands")
        require(commands.length() <= 5)
        for (i in 0 until commands.length()) handle(current, commands.getJSONObject(i))
    }
    private suspend fun handle(current: Active, command: JSONObject) {
        val id = command.getString("id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val lease = command.getString("lease_token").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val kind = command.getString("kind")
        val prior = current.session.receipts.firstOrNull { it.id == id }
        if(kind in listOf("get_support_bundle","capture_app_screen")) {
            if(prior!=null){acknowledge(current,lease,prior);return}
            val expires=parseManagementTime(command.getString("expires_at"));require(expires>System.currentTimeMillis())
            beginCommand(current,id,lease)
            change(current){it.copy(receipts=(it.receipts+CommandReceipt(id,"failed","Support request interrupted; no automatic replay or repeated consent prompt")).takeLast(512))}
            val result=try {
                val profile=profiles.getProfileId()
                fun eligible():Boolean = active===current&&current.session.policy=="active"&&profiles.getProfileIdSync()==profile&&expires>System.currentTimeMillis()
                val body=JSONObject().put("command_id",id).put("lease_token",lease).put("profile_ref",managementHash(profile))
                if(kind=="capture_app_screen") {
                    val image=withTimeoutOrNull(20_000){ManagementForeground.capture(minOf(expires,System.currentTimeMillis()+20_000),::eligible)}
                    require(image!=null&&eligible()&&image.size<=131072)
                    body.put("kind","panel").put("consented",true).put("png_base64",Base64.encodeToString(image,Base64.NO_WRAP))
                } else {
                    val receipt=ManagementActions(context,repository.get(),profiles,current.session.catalogSecret.orEmpty()).execute(id,"get_diagnostics",current.account.scope)
                    require(receipt.status=="succeeded"&&receipt.snapshot!=null&&eligible())
                    body.put("kind","bundle").put("document",JSONObject().put("schema_version",1).put("profile_ref",managementHash(profile)).put("snapshot",JSONObject(receipt.snapshot)).put("events",ManagementEvents.export(current.account.scope)))
                }
                require(eligible()&&managementAccount(repository.get().observeConfig().first())?.scope==current.account.scope)
                api.control("/api/device/support/upload",body,current.session.credential)
                CommandReceipt(id,"succeeded",if(kind=="capture_app_screen")"Customer-consented safe support-panel image saved privately; TV video, current page and account screens excluded" else "Private redacted support bundle saved; fixed-code device events only, not raw logs or provider reachability proof")
            } catch(cancelled: CancellationException){throw cancelled}catch(_: Exception){CommandReceipt(id,"failed",if(kind=="capture_app_screen")"Panel capture declined, expired, unavailable or target changed; no successful image upload confirmed" else "Support bundle unavailable; no successful upload confirmed")}
            change(current){it.copy(receipts=it.receipts.map { old->if(old.id==id)result else old })}
            acknowledge(current,lease,result);return
        }
        if (kind in listOf("get_preferences","apply_preferences")) {
            if(prior!=null){acknowledge(current,lease,prior);return}
            require(parseManagementTime(command.getString("expires_at"))>System.currentTimeMillis())
            beginCommand(current,id,lease)
            change(current){it.copy(receipts=(it.receipts+CommandReceipt(id,"failed","Settings action interrupted; read fresh effective settings before retrying")).takeLast(512))}
            val result=try {
                withTimeoutOrNull(15_000) {
                    val profile=profiles.getProfileId()
                    val matches: (com.arflix.tv.data.repository.IptvConfig)->Boolean={managementAccount(it)?.scope==current.account.scope}
                    val before=repository.get().readManagedPreferenceState(profile,matches)
                    val initial=managedPreferencesDocument(current.session.catalogSecret.orEmpty(),profile,current.account.scope,before)
                    if(kind=="apply_preferences") {
                        val ctx=api.control("/api/device/preferences/context",JSONObject().put("command_id",id).put("lease_token",lease),current.session.credential)
                        require(ctx.getString("profile_ref")==initial.profile_ref&&ctx.getString("expected_revision")==initial.revision)
                        val target=ctx.getJSONObject("target")
                        fun values(key: String):Map<String,String> {val obj=target.getJSONObject(key);return obj.keys().asSequence().associateWith { obj.getString(it) }}
                        require(repository.get().compareAndSetManagedPreferenceState(profile,before,values("base_values"),values("overrides"),target.getString("base_preset_id"),target.getInt("base_version"),matches))
                    }
                    val after=managedPreferencesDocument(current.session.catalogSecret.orEmpty(),profile,current.account.scope,repository.get().readManagedPreferenceState(profile,matches))
                    api.control("/api/device/preferences/capture",JSONObject().put("command_id",id).put("lease_token",lease).put("document",JSONObject(Gson().toJson(after))),current.session.credential)
                    CommandReceipt(id,"succeeded",if(kind=="get_preferences")"Fresh effective profile settings and value origins captured" else "Device/profile settings atomically applied and read back; unrelated values and central update policy untouched")
                } ?: CommandReceipt(id,"failed","Settings action timed out; read fresh effective settings to verify actual state")
            }catch(cancelled: CancellationException){throw cancelled}catch(_: Exception){CommandReceipt(id,"failed","Settings action unavailable or stale; read fresh effective settings to verify actual state")}
            change(current){it.copy(receipts=it.receipts.map { old->if(old.id==id)result else old })}
            acknowledge(current,lease,result);return
        }
        if (kind in listOf("get_sources", "apply_sources")) {
            if (prior != null) { acknowledge(current, lease, prior); return }
            require(parseManagementTime(command.getString("expires_at")) > System.currentTimeMillis())
            beginCommand(current, id, lease)
            change(current) { it.copy(receipts = (it.receipts + CommandReceipt(id,"failed","Source action interrupted; read fresh sources before retrying")).takeLast(512)) }
            val result = try {
                withTimeoutOrNull(30_000) {
                    val profile = profiles.getProfileId()
                    val accountMatches: (com.arflix.tv.data.repository.IptvConfig) -> Boolean = { managementAccount(it)?.scope == current.account.scope }
                    suspend fun snapshot(): ManagedSourceSnapshot {
                        val config = repository.get().readManagedSources(profile, accountMatches)
                        return ManagedSourceSnapshot(profile_ref = managementHash(profile), revision = managedSourcesRevision(current.session.catalogSecret.orEmpty(), managementHash(profile), config), config = config)
                    }
                    suspend fun upload(doc: ManagedSourceSnapshot, reason: String) {
                        api.control("/api/device/sources/capture", JSONObject().put("command_id",id).put("lease_token",lease).put("reason",reason)
                            .put("snapshot",JSONObject(Gson().toJson(doc))), current.session.credential)
                    }
                    val before = snapshot()
                    if (kind == "get_sources") upload(before,"read") else {
                        val ctx = api.control("/api/device/sources/context",JSONObject().put("command_id",id).put("lease_token",lease),current.session.credential)
                        require(ctx.getString("profile_ref") == before.profile_ref && ctx.getString("expected_revision") == before.revision)
                        val original = Gson().fromJson(ctx.getJSONObject("before").toString(), com.arflix.tv.data.repository.IptvConfig::class.java)
                        val target = Gson().fromJson(ctx.getJSONObject("target").toString(), com.arflix.tv.data.repository.IptvConfig::class.java)
                        val restoreCheckpoint=ctx.optBoolean("restore_checkpoint",false)
                        require(original == before.config && validManagedSourceConfig(original,target,restoreCheckpoint))
                        if(!repository.get().isStableManagedSourceTarget(target))return@withTimeoutOrNull CommandReceipt(id,"failed","Source input needs local normalization; no changes applied. Use canonical M3U/xmltv URLs and URL-safe Xtream credentials, then preview again.")
                        // The server must retain a safety preimage BEFORE the one atomic write.
                        upload(before,"pre_apply")
                        require(repository.get().compareAndSetManagedSources(profile,original,target,restoreCheckpoint,accountMatches))
                        upload(snapshot(),"confirmation")
                    }
                    CommandReceipt(id,"succeeded",if(kind=="get_sources") "Fresh source configuration saved privately; browser receives masked metadata only" else "Source configuration atomically saved and read back; retained guides/favourites kept. Provider health is not confirmed.")
                } ?: CommandReceipt(id,"failed","Source action timed out; read fresh sources to verify actual state. Safety history retained.")
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {CommandReceipt(id,"failed","Source action unavailable or stale; read fresh sources to verify actual state. No credentials disclosed.")}
            change(current) { it.copy(receipts = it.receipts.map { old -> if(old.id==id)result else old }) }
            acknowledge(current,lease,result);return
        }
        if(kind in listOf("backup_favourites","preview_favourites_restore","restore_favourites")) {
            if(prior!=null && !(kind=="preview_favourites_restore" && prior.status=="succeeded")) {acknowledge(current,lease,prior);return}
            beginCommand(current,id,lease)
            val payload=command.optJSONObject("payload") ?: JSONObject()
            val args=payload.keys().asSequence().associateWith { key->(payload.opt(key) as? String).orEmpty() }.toMutableMap()
            if(kind=="preview_favourites_restore" && prior!=null)args["backup_id"]=prior.favouritesBackupId.orEmpty()
            change(current){it.copy(receipts=(it.receipts.filterNot { old->old.id==id }+CommandReceipt(id,"failed","Recovery interrupted; request a new preview before retrying")).takeLast(512))}
            val executed=try {
                if(kind=="backup_favourites") {
                    val saved=captureFavourites(current,"manual",true)
                    if(saved==null)CommandReceipt(id,"failed","Favourites capture unavailable; previous good backups retained") else CommandReceipt(id,"succeeded",if(saved.optBoolean("stored"))"Nonempty favourites backup saved; empty states never replace it" else "Current favourites empty; no good backup was replaced")
                } else {
                    val context=api.control("/api/device/favourites/context",JSONObject().apply {if(kind=="preview_favourites_restore")put("backup_id",args["backup_id"]) else put("preview_id",args["preview_id"])},current.session.credential)
                    if(kind=="preview_favourites_restore")require(context.getString("backup_id")==args["backup_id"]) else require(context.getString("preview_id")==args["preview_id"])
                    val backup=Gson().fromJson(context.getJSONObject("backup").toString(),ManagementFavouriteSnapshot::class.java)
                    val preview=if(context.has("preview"))Gson().fromJson(context.getJSONObject("preview").toString(),ManagementFavouritePreview::class.java) else null
                    if(kind=="restore_favourites") { args["backup_id"]=context.getString("backup_id");require(captureFavourites(current,"pre_restore",true)!=null) }
                    withTimeoutOrNull(20000) { ManagementFavouriteActions(repository.get(),profiles,current.session.catalogSecret.orEmpty()).execute(id,kind,current.account.scope,args,backup,preview) }
                        ?: CommandReceipt(id,"failed","Favourites recovery timed out; request a fresh preview before retrying")
                }
            } catch(cancelled: CancellationException){throw cancelled} catch(_: Exception){CommandReceipt(id,"failed","Favourites recovery context/safety backup unavailable; no blind restore performed")}
            change(current){it.copy(receipts=it.receipts.map { old->if(old.id==id)executed.copy(favouritesPreview=null) else old })}
            acknowledge(current,lease,executed);return
        }
        if(prior!=null && kind in listOf("get_categories","get_category_layout") && prior.status=="succeeded") {
            beginCommand(current,id,lease)
            val target=categoryRetryPayload(prior,kind)
            val refreshed=if(target==null)CommandReceipt(id,"failed","Original category target is unavailable; request a new snapshot") else
                ManagementActions(context,repository.get(),profiles,current.session.catalogSecret.orEmpty()).execute(id,kind,current.account.scope,target)
            change(current){it.copy(receipts=it.receipts.map { old -> if(old.id==id)refreshed.copy(catalog=null) else old })}
            acknowledge(current,lease,refreshed);return
        }
        val receipt = if (prior != null) prior else {
            val payload = command.optJSONObject("payload") ?: JSONObject()
            val item = ManagementCommand(id, kind, runCatching { parseManagementTime(command.getString("expires_at")) }.getOrDefault(0),
                payload.optString("message", ""), payload.optInt("duration_seconds", 0))
            if (kind == "restart_app" && item.expiresAtMs > System.currentTimeMillis()) {
                beginCommand(current, id, lease)
                val now = System.currentTimeMillis()
                val eligible = active === current && current.session.policy == "active" &&
                    managementAccount(repository.get().observeConfig().first())?.scope == current.account.scope &&
                    now - current.session.lastReopenAtMs >= 300_000 && ManagementForeground.eligible()
                val result = CommandReceipt(id, if (eligible) "succeeded" else "failed", if (eligible)
                    "Foreground app-screen reopen requested after receipt; not a process/device reboot or guarantee. If the app pauses or acknowledgment is lost, no retry is performed."
                    else "Reopen unavailable: app must be resumed, updates idle and no reopen within five minutes")
                // Consumed BEFORE network acknowledgment/effect. Duplicate delivery only
                // re-ACKs this receipt; never arms or replays a disruptive operation.
                change(current) { it.copy(receipts = (it.receipts + result).takeLast(512), lastReopenAtMs = if (eligible) now else it.lastReopenAtMs) }
                acknowledge(current, lease, result)
                if(eligible)ManagementEvents.record(ManagementEventCode.REOPEN_REQUESTED)
                if (eligible && active === current && item.expiresAtMs > System.currentTimeMillis() &&
                    managementAccount(repository.get().observeConfig().first())?.scope == current.account.scope) ManagementForeground.reopen()
                return
            }
            if (kind in listOf("refresh_playlist", "reload_epg", "get_settings", "get_diagnostics", "set_settings", "set_live_buffer", "clear_cache", "get_categories", "set_categories", "get_category_layout", "apply_category_layout") && item.expiresAtMs > System.currentTimeMillis()) {
                beginCommand(current, id, lease)
                // Persist an interrupted-operation receipt BEFORE side effects. A
                // process/network retry cannot silently start an expensive job twice.
                val interrupted = CommandReceipt(id, "failed", "Action interrupted; request a new command to retry safely")
                change(current) { it.copy(receipts = (it.receipts + interrupted).takeLast(512)) }
                val settings = if (kind in listOf("set_settings","set_live_buffer","get_categories","set_categories","get_category_layout","apply_category_layout")) payload.keys().asSequence().associateWith { key ->
                    val value=payload.opt(key);if(value is String || (kind!="set_settings" && key=="page" && value is Int) || (kind=="apply_category_layout" && ((key in listOf("order","hidden") && value is JSONArray)||(key=="provider_order" && value is Boolean))))value.toString() else ""
                } else emptyMap()
                val executed = ManagementActions(context, repository.get(), profiles,current.session.catalogSecret.orEmpty()).execute(id, kind, current.account.scope, settings)
                // Catalog pages are transient. Persist only a read's scoped retry
                // target, not hundreds of full pages in the duplicate journal.
                change(current) { it.copy(receipts = it.receipts.map { old -> if (old.id == id) executed.copy(catalog=null) else old }) }
                acknowledge(current, lease, executed)
                return
            }
            var result: CommandReceipt? = null
            if (kind in listOf("request_checkin", "show_message") && item.expiresAtMs > System.currentTimeMillis()) beginCommand(current, id, lease)
            // Persist before acknowledgment: a retry after process/network failure
            // returns the same result and does not redisplay a dismissed message.
            change(current) { val plan = planManagementCommand(it, item, System.currentTimeMillis()); result = plan.receipt; plan.session }
            result ?: return
        }
        acknowledge(current, lease, receipt)
    }
    private suspend fun acknowledge(current: Active, lease: String, receipt: CommandReceipt) {
        val body = JSONObject().put("schema_version", 1).put("lease_token", lease).put("status", receipt.status).put("summary", receipt.summary)
        receipt.snapshot?.let { body.put("snapshot", JSONObject(it)) }
        receipt.catalog?.let { body.put("catalog",it.json()) }
        receipt.favouritesPreview?.let { body.put("favourites_preview",it.json()) }
        api.control("/api/device/commands/${receipt.id}/ack", body, current.session.credential)
        ManagementEvents.record(if(receipt.status=="succeeded")ManagementEventCode.RECEIPT_SUCCEEDED else ManagementEventCode.RECEIPT_FAILED)
    }
    private suspend fun beginCommand(current: Active, id: String, lease: String) {
        // Revalidate the lease/current policy immediately before starting. A
        // cancelled former-reseller delivery must not run merely because it was
        // present in an earlier check-in response. Already-running work cannot
        // be recalled atomically by a web request.
        api.control("/api/device/commands/$id/ack", JSONObject().put("schema_version", 1).put("lease_token", lease)
            .put("status", "running").put("summary", "App accepted the current command lease"), current.session.credential)
        ManagementEvents.record(ManagementEventCode.COMMAND_ACCEPTED)
    }
    private fun newInstallation() = UUID.randomUUID().toString().replace("-", "")
    private suspend fun captureFavourites(current: Active,reason: String,force: Boolean): JSONObject? {
        val snapshot=withTimeoutOrNull(5000) {ManagementFavouriteActions(repository.get(),profiles,current.session.catalogSecret.orEmpty()).capture(current.account.scope)}
        if(snapshot==null){current.favouritesStatus="Capture timed out; existing backups retained";publish(current);return null}
        val key=snapshot.profile_ref+":"+snapshot.source_ref;val memo=current.session.favouritesSync?.get(key);val now=System.currentTimeMillis()
        if(!force && memo!=null && memo.substringBefore(':')==snapshot.content_hash && now-(memo.substringAfter(':').toLongOrNull() ?: 0L) in 0..21600000L) {
            current.favouritesStatus=if(snapshot.entries.isEmpty())"Currently empty; previous good backups retained" else "Unchanged; last saved favourites retained";publish(current);return null
        }
        val result=api.control("/api/device/favourites/capture",snapshot.json().put("reason",reason),current.session.credential)
        require(result.getInt("schema_version")==1 && result.getString("profile_ref")==snapshot.profile_ref && result.getString("source_ref")==snapshot.source_ref)
        change(current){session->val sync=session.favouritesSync.orEmpty().toMutableMap();sync.remove(key);sync[key]=snapshot.content_hash+":"+now;while(sync.size>32)sync.remove(sync.keys.first());session.copy(favouritesSync=sync)}
        current.favouritesStatus=if(result.optBoolean("stored"))"Saved ${snapshot.entries.size} favourites; good backups protected" else "Currently empty; previous good backups retained";publish(current)
        return result
    }
}

internal fun parseManagementTime(value: String): Long {
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)?.time ?: error("Invalid management timestamp")
}
