package com.arflix.tv.ui.screens.xtreamgate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.HomeServerRepository
import com.arflix.tv.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fixed, non-configurable Jellyfin server for Extreme TV Network. Only username and
 * password are ever asked for — this screen never shows or accepts a server address.
 */
private const val JELLYFIN_GATE_SERVER_URL = "http://38.127.60.212:8096"

data class JellyfinGateUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class JellyfinGateViewModel @Inject constructor(
    private val homeServerRepository: HomeServerRepository,
    private val catalogRepository: CatalogRepository,
    private val iptvRepository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(JellyfinGateUiState())
    val uiState: StateFlow<JellyfinGateUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, errorMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, errorMessage = null)
    }

    fun submit(onDone: () -> Unit) {
        val current = _uiState.value
        if (current.isSubmitting) return

        val username = current.username.trim()
        val password = current.password

        if (username.isBlank() || password.isBlank()) {
            _uiState.value = current.copy(errorMessage = "Enter your username and password.")
            return
        }

        _uiState.value = current.copy(isSubmitting = true, errorMessage = null)

        viewModelScope.launch {
            val result = homeServerRepository.connect(
                JELLYFIN_GATE_SERVER_URL,
                username,
                password,
                "Extreme TV Network"
            )
            result.onSuccess {
                // Connecting alone doesn't make anything show up on Home — the same
                // catalog-sync step the Settings screen runs after a successful
                // connect is needed here too, or Movies/Shows from this server never
                // appear until the user happens to open Settings and trigger it
                // themselves.
                runCatching {
                    val candidates = homeServerRepository.getCatalogCandidates()
                    catalogRepository.syncHomeServerCatalogs(candidates)
                }
                finish(onDone)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = error.message ?: "Couldn't connect. Check your username and password."
                )
            }
        }
    }

    /**
     * Jellyfin is optional too — this screen can be skipped entirely.
     */
    fun skip(onDone: () -> Unit) {
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch { finish(onDone) }
    }

    private suspend fun finish(onDone: () -> Unit) {
        // Marks the whole two-screen onboarding gate (Xtream + Jellyfin) as done,
        // regardless of what was actually configured on either screen, so it never
        // shows again on this device.
        iptvRepository.markGateOnboardingComplete()
        _uiState.value = _uiState.value.copy(isSubmitting = false)
        onDone()
    }
}
