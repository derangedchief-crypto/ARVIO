package com.arflix.tv.management

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.arflix.tv.BuildConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ProfileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Explicit app operations only. No URLs, credentials, source labels, profile names,
 * free-form errors/logs, favourite values or update controls leave the device. */
internal class ManagementActions(private val context: Context, private val repository: IptvRepository, private val profiles: ProfileManager,private val catalogSecret: String = "") {
    suspend fun execute(id: String, kind: String, accountScope: String, payload: Map<String,String> = emptyMap()): CommandReceipt {
        val profile = profiles.getProfileId()
        suspend fun eligible(): Boolean = profiles.getProfileId() == profile && managementAccount(repository.observeConfig().first())?.scope == accountScope
        if (!eligible()) return CommandReceipt(id, "failed", "Active profile/account changed; action not applied")
        return try {
            if (kind == "set_live_buffer") {
                if (!validManagedLiveBufferPayload(payload) || payload["profile_ref"] != managementHash(profile)) return CommandReceipt(id, "failed", "Buffer target does not match the active profile")
                val before = repository.livePrebufferSeconds()
                if (payload["expected_revision"] != managementLiveBufferRevision(managementHash(profile), accountScope, before)) return CommandReceipt(id, "failed", "Playback buffer changed; request a fresh settings snapshot")
                if (!repository.compareAndSetManagedLivePrebuffer(profile, before, payload.getValue("prebuffer_seconds").toInt()) { managementAccount(it)?.scope == accountScope }) return CommandReceipt(id, "failed", "Profile, account or playback buffer changed; request a fresh settings snapshot")
            }
            if (kind == "set_settings") {
                if (!validManagedSortPayload(payload) || payload["profile_ref"] != managementHash(profile)) return CommandReceipt(id, "failed", "Settings target does not match the active profile")
                val before = repository.observeConfig().first().sortOrder
                if (payload["expected_revision"] != managementSettingsRevision(managementHash(profile), accountScope, before)) return CommandReceipt(id, "failed", "Settings changed locally; request a fresh snapshot first")
                val applied = repository.compareAndSetManagedSortOrder(profile, before, payload.getValue("sort_order")) { managementAccount(it)?.scope == accountScope }
                if (!applied) return CommandReceipt(id, "failed", "Profile, source account or sort order changed; request a fresh snapshot")
            }
            val receipt = when (kind) {
                "clear_cache" -> withTimeout(10_000) {
                    val report = clearManagementExpendableCache(context)
                    if(!report.succeeded)ManagementEvents.record(ManagementEventCode.CACHE_CLEAR_PARTIAL)
                    CommandReceipt(id, if (report.succeeded) "succeeded" else "failed", report.summary)
                }
                "get_categories", "set_categories", "get_category_layout", "apply_category_layout" -> withTimeout(20_000) { ManagementCategoryActions(repository,profiles,catalogSecret).execute(id,kind,accountScope,payload) }
                "get_settings", "set_settings", "set_live_buffer" -> {
                    val config = repository.observeConfig().first()
                    val playlists = if (config.playlists.isEmpty()) if (config.m3uUrl.isBlank()) 0 else 1 else config.playlists.size
                    val enabled = if (config.playlists.isEmpty()) playlists else config.playlists.count { it.enabled }
                    val prebuffer = repository.livePrebufferSeconds()
                    CommandReceipt(id, "succeeded", if (kind == "set_live_buffer") "Device Live TV pre-buffer saved; applies on the next standard live channel tune, not the currently playing stream or DRM/catch-up/VOD" else if (kind == "set_settings") "Channel sorting updated for the selected profile; other settings unchanged" else "Read-only active-profile settings snapshot", mapOf(
                        "schema_version" to "1", "profile_ref" to managementHash(profile), "sort_order" to config.sortOrder,
                        "settings_revision" to managementSettingsRevision(managementHash(profile), accountScope, config.sortOrder),
                        "playlist_count" to playlists.toString(), "enabled_playlist_count" to enabled.toString(),
                        "portal_count" to config.stalkerPortals.size.toString(),
                        "favourite_channel_count" to repository.observeFavoriteChannels().first().size.toString(),
                        "favourite_group_count" to repository.observeFavoriteGroups().first().size.toString(),
                        "live_prebuffer_seconds" to prebuffer.toString(),
                        "live_buffer_revision" to managementLiveBufferRevision(managementHash(profile), accountScope, prebuffer)))
                }
                "get_diagnostics" -> {
                    val disk = StatFs(context.filesDir.absolutePath)
                    val guide = repository.guideRefreshState.value
                    CommandReceipt(id, "succeeded", "Redacted app diagnostics; Android network validation is not a provider reachability test. No logs or screenshots collected", mapOf(
                        "schema_version" to "1", "profile_ref" to managementHash(profile), "app_package" to context.packageName,
                        "app_version" to BuildConfig.VERSION_NAME, "sdk" to Build.VERSION.SDK_INT.toString(),
                        "storage_total" to disk.totalBytes.toString(), "storage_free" to disk.availableBytes.toString(),
                        "channel_count" to repository.pagedChannelStoreCount().toString(),
                        "guide_state" to if (guide.loading) "loading" else if (guide.failed) "failed" else "idle") + managementRuntimeDiagnostics(context))
                }
                "refresh_playlist" -> {
                    val snapshot = withTimeout(45_000) { repository.loadSnapshot(forcePlaylistReload = true, forceEpgReload = false, allowNetworkEpgFetch = false, backgroundEpgWork = true) }
                    ManagementEvents.record(ManagementEventCode.PLAYLIST_REFRESH_OK)
                    CommandReceipt(id, "succeeded", "Playlist reload completed; ${snapshot.channels.size} channels available (may include retained data). Saved guide retained.")
                }
                "reload_epg" -> {
                    repository.requestGuideRefresh(force = true)
                    ManagementEvents.record(ManagementEventCode.GUIDE_REFRESH_REQUESTED)
                    CommandReceipt(id, "succeeded", "Guide refresh requested through the shared background job. This is NOT confirmation of a completed import; saved listings remain available.")
                }
                else -> CommandReceipt(id, "failed", "Unsupported action")
            }
            if (eligible()) receipt else CommandReceipt(id, "failed", "Active profile/account changed during action; any settings write remained scoped to the original profile; no snapshot published")
        } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
            CommandReceipt(id, "failed", "Action timed out; saved configuration and guide were not cleared")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CommandReceipt(id, "failed", "Action unavailable; saved configuration and guide were not cleared") }
    }
}
