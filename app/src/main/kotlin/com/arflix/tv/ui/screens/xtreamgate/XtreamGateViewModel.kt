package com.arflix.tv.ui.screens.xtreamgate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.repository.IptvPlaylistEntry
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.XtreamEntitlementsRepository
import com.arflix.tv.data.repository.XtreamEntitlementsResult
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Fixed, non-configurable Xtream host for Extreme TV Network. Kept in sync with the
 * same constant used in SettingsScreen.kt (FIXED_XTREAM_HOST_URL) — if that value
 * ever changes, update it here too.
 */
private const val XTREAM_GATE_HOST_URL = "https://tv.extremeiptv.net"

/** Hard ceilings so a dead manifest host can never stall sign-in. */
private const val ENTITLEMENT_INSTALL_TIMEOUT_MS = 15_000L
private const val ENTITLEMENT_REMOVE_TIMEOUT_MS = 5_000L

data class XtreamGateUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val progressText: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class XtreamGateViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val entitlementsRepository: XtreamEntitlementsRepository,
    // Lazy on purpose: StreamRepository is a heavy singleton (addon health load,
    // quality-filter warmup, Telegram resolver) and this gate is on the cold-start
    // path. Only construct it if the package actually grants or revokes an addon.
    private val streamRepository: Lazy<StreamRepository>
) : ViewModel() {

    private val _uiState = MutableStateFlow(XtreamGateUiState())
    val uiState: StateFlow<XtreamGateUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, errorMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, errorMessage = null)
    }

    fun submit(onSuccess: () -> Unit) {
        val current = _uiState.value
        if (current.isSubmitting) return
        val username = current.username.trim()
        val password = current.password
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = current.copy(errorMessage = "Enter your username and password.")
            return
        }
        _uiState.value = current.copy(isSubmitting = true, errorMessage = null, progressText = "Signing in…")
        viewModelScope.launch {
            try {
                val result = iptvRepository.verifyXtreamLogin(XTREAM_GATE_HOST_URL, username, password)
                if (!result.success) {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        progressText = null,
                        errorMessage = result.message ?: "Invalid username or password."
                    )
                    return@launch
                }

                // This gate runs before profile selection, but IPTV config is stored
                // per-profile (key = "profile__iptv_playlists_json"). Without
                // resolving a real, permanently-active profile first, the playlist
                // saved below gets written under a placeholder profile id, then
                // becomes invisible the moment the user's actual profile (a different
                // id, created on the next screen) becomes active. Ensuring the active
                // profile now — and making it the one that sticks — keeps the two in
                // sync.
                ensureActiveProfile()

                val combined = "$XTREAM_GATE_HOST_URL $username $password"
                val entry = IptvPlaylistEntry(
                    id = "list_1",
                    name = "Extreme TV Network",
                    m3uUrl = combined,
                    epgUrl = combined,
                    enabled = true,
                    epgUrls = listOf(combined)
                )
                iptvRepository.savePlaylists(listOf(entry))

                // Saving the playlist config alone does not fetch anything — it just
                // persists what to load. We do need the channel LIST fetched (fast —
                // just the Xtream API's get_live_categories + get_live_streams calls)
                // so Live TV isn't empty, but the full EPG/guide fetch is a separate,
                // much slower thing (this provider's guide is 100MB+) and is
                // deliberately NOT forced here. Every other screen in this app that
                // touches IPTV (TvViewModel's own refresh(), Settings' refreshIptv())
                // also passes allowNetworkEpgFetch = false for exactly this reason —
                // the full guide backfill instead runs as a genuine background job
                // (TvViewModel.completeEpgBackfillJob) once the user opens Live TV,
                // with a long timeout and an idle delay, never blocking any UI. Forcing
                // it here at login was making sign-in take minutes for no reason.
                runCatching { iptvRepository.purgeAllIptvSourceCaches() }
                val snapshot = iptvRepository.loadSnapshot(
                    forcePlaylistReload = true,
                    forceEpgReload = false,
                    allowNetworkEpgFetch = false,
                    onProgress = { progress ->
                        _uiState.value = _uiState.value.copy(progressText = progress.message)
                    }
                )

                if (snapshot.channels.isEmpty()) {
                    // Credentials were valid and the playlist saved, but nothing came
                    // back from the provider — surface this instead of silently
                    // dropping the user into an empty Live TV screen. Entitlements are
                    // deliberately left untouched: with no categories there is nothing
                    // to match against, and revoking on "unknown" would strip a paid
                    // addon after a provider hiccup.
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        progressText = null,
                        errorMessage = "Signed in, but no channels came back from the server. " +
                            "Try again from Settings \u2192 Refresh IPTV."
                    )
                    onSuccess()
                    return@launch
                }

                // Package-driven addon entitlements. Runs after the channel load
                // because that load is where the provider's category list — the
                // entitlement signal — comes from. Never fails the sign-in.
                runCatching { applyPackageEntitlements(snapshot, result.packageLabels) }
                    .onFailure { error ->
                        System.err.println("[XtreamGate] entitlement apply failed: ${error.message}")
                    }

                // Warm VOD caches in the background — nice to have, not worth blocking
                // navigation for.
                launch { runCatching { iptvRepository.warmXtreamVodCachesIfPossible() } }

                _uiState.value = _uiState.value.copy(isSubmitting = false, progressText = null)
                onSuccess()
            } catch (e: Exception) {
                // Never fail silently — an unhandled exception here would otherwise
                // leave the button looking "unresponsive" with no feedback at all.
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    progressText = null,
                    errorMessage = "Something went wrong: ${e.message ?: e::class.simpleName}"
                )
            }
        }
    }

    /**
     * Installs the Stremio addons the user's Xtream package grants, and removes the
     * ones it no longer does.
     *
     * Costs no extra network calls: the labels come from data the sign-in already
     * fetched — the provider's live category names (which the Xtream catalog load
     * stores as each channel's group, so [IptvSnapshot.grouped] is keyed by them)
     * plus every string the player_api.php login response returned. Both are
     * server-side filtered by the line's bouquets, so a marker category present
     * only in the paid packages is a signal the client cannot fake.
     *
     * Uses ensureCustomAddons() rather than addCustomAddon() so a re-login does not
     * reinstate an addon the user deliberately disabled: ensureCustomAddons only
     * fetches the manifest when nothing with that URL is installed yet.
     *
     * Addons are account-level (shared_installed_addons_v1), not profile-scoped, so
     * unlike the IPTV playlist above this does not depend on the active profile.
     */
    private suspend fun applyPackageEntitlements(
        snapshot: IptvSnapshot,
        loginLabels: List<String>
    ) {
        val labels = buildList {
            // grouped is already keyed by provider category name, so this is O(#groups)
            // rather than a pass over every channel.
            addAll(snapshot.grouped.keys)
            if (snapshot.grouped.isEmpty()) {
                snapshot.channels.forEach { add(it.group) }
            }
            addAll(loginLabels)
        }

        val lookup = entitlementsRepository.resolve(labels)
        if (lookup is XtreamEntitlementsResult.Unresolved) {
            System.err.println(
                "[XtreamGate] entitlements unresolved (${lookup.reason}); leaving addons unchanged"
            )
            return
        }

        val resolved = lookup as XtreamEntitlementsResult.Resolved
        System.err.println(
            "[XtreamGate] entitlements: labels=${resolved.labelCount} " +
                "match=${resolved.matchedLabel} granted=${resolved.granted.size} " +
                "revoked=${resolved.revoked.size}"
        )
        if (resolved.granted.isEmpty() && resolved.revoked.isEmpty()) return

        val repository = streamRepository.get()

        if (resolved.granted.isNotEmpty()) {
            val names = resolved.granted.joinToString(", ") { it.displayName }
            _uiState.value = _uiState.value.copy(progressText = "Unlocking $names…")
            val results = withTimeoutOrNull(ENTITLEMENT_INSTALL_TIMEOUT_MS) {
                repository.ensureCustomAddons(resolved.granted.map { it.manifestUrl })
            }
            if (results == null) {
                System.err.println("[XtreamGate] entitlement install timed out for: $names")
            } else {
                // Not indexed against `granted`: ensureCustomAddons() normalizes and
                // de-duplicates the URLs it was given, so its result list is not
                // guaranteed to be positionally aligned with the input.
                results.forEach { result ->
                    result.onSuccess { addon ->
                        System.err.println("[XtreamGate] entitlement addon ready: ${addon.name} (${addon.id})")
                    }.onFailure { error ->
                        System.err.println("[XtreamGate] entitlement addon failed: ${error.message}")
                    }
                }
            }
        }

        if (resolved.revoked.isNotEmpty()) {
            // Package no longer carries the marker — drop the addon so a downgrade
            // actually takes effect instead of leaving a paid addon installed forever.
            withTimeoutOrNull(ENTITLEMENT_REMOVE_TIMEOUT_MS) {
                repository.removeCustomAddonsByUrl(resolved.revoked)
            }
        }
    }

    /**
     * Makes sure a real profile exists and is marked active, and that
     * ProfileManager's cached id is updated to match immediately (not just
     * eventually, once its Flow collects) so every profile-scoped save that
     * happens right after this call lands in the right place.
     */
    private suspend fun ensureActiveProfile() {
        val profile = profileRepository.createDefaultProfileIfNeeded()
            ?: profileRepository.getActiveProfile()
            ?: profileRepository.getProfiles().firstOrNull()
            ?: return
        profileRepository.setActiveProfile(profile.id)
        profileManager.setCurrentProfileId(profile.id)
        profileManager.setCurrentProfileName(profile.name)
    }

    /**
     * Xtream is optional — this screen can be skipped entirely. Still resolves the
     * active profile first, since the next screen (Jellyfin) and Home both depend
     * on one being set, regardless of whether Xtream itself was configured.
     */
    fun skip(onSkip: () -> Unit) {
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            ensureActiveProfile()
            onSkip()
        }
    }
}
