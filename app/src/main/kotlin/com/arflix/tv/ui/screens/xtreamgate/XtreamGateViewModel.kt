package com.arflix.tv.ui.screens.xtreamgate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.IptvPlaylistEntry
import com.arflix.tv.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fixed, non-configurable Xtream host for Extreme TV Network. Kept in sync with the
 * same constant used in SettingsScreen.kt (FIXED_XTREAM_HOST_URL) — if that value
 * ever changes, update it here too.
 */
private const val XTREAM_GATE_HOST_URL = "https://tv.extremeiptv.net"

data class XtreamGateUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class XtreamGateViewModel @Inject constructor(
    private val iptvRepository: IptvRepository
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

        _uiState.value = current.copy(isSubmitting = true, errorMessage = null)

        viewModelScope.launch {
            val result = iptvRepository.verifyXtreamLogin(XTREAM_GATE_HOST_URL, username, password)
            if (!result.success) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = result.message ?: "Invalid username or password."
                )
                return@launch
            }

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

            _uiState.value = _uiState.value.copy(isSubmitting = false)
            onSuccess()
        }
    }
}
