package com.arflix.tv.ui.components

import android.app.Application
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arflix.tv.management.DeviceManagementState
import com.arflix.tv.management.ManagementMessage
import com.arflix.tv.management.preparingManagementAccount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceManagementGateTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unpairedOrUnavailableControlCenterDoesNotBecomeAnActivationGate() {
        compose.setContent { DeviceManagementGate(DeviceManagementState(false, status = "Offline"), {}) { Text("Normal app") } }
        compose.onNodeWithText("Normal app").assertExists()
        compose.onNodeWithText("Service suspended").assertDoesNotExist()
    }
    @Test fun activatedPlaylistIdentityCheckKeepsTheOnboardingCompositionAlive() {
        val state = mutableStateOf(DeviceManagementState(false, status = "No eligible activation account"))
        var created = 0; var disposed = 0
        compose.setContent {
            DeviceManagementGate(state.value, {}) {
                DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                Text("Activation entered once; signing in")
            }
        }
        compose.runOnIdle { state.value = preparingManagementAccount(state.value, false) }
        compose.onNodeWithText("Activation entered once; signing in").assertExists()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed) }
        compose.runOnIdle { state.value = state.value.copy(status = "Awaiting enrollment", checkingLocalState = false) }
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed) }
    }
    @Test fun knownSuspensionDisposesPlaybackAndBackDoesNotExposeIt() {
        val state = mutableStateOf(DeviceManagementState(false))
        var releases = 0
        var owner: OnBackPressedDispatcherOwner? = null
        compose.setContent {
            owner = LocalOnBackPressedDispatcherOwner.current
            DeviceManagementGate(state.value, {}) {
                DisposableEffect(Unit) { onDispose { releases++ } }
                Text("Player and navigation")
            }
        }
        compose.runOnIdle { state.value = DeviceManagementState(false, "device-id", "Checking provider", "suspended") }
        compose.onNodeWithText("Player and navigation").assertDoesNotExist()
        compose.onNodeWithText("Service suspended").assertExists()
        compose.runOnIdle { assertEquals(1, releases); assertNotNull(owner); owner!!.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Service suspended").assertExists()
        compose.runOnIdle { state.value = state.value.copy(policy = "active") }
        compose.onNodeWithText("Player and navigation").assertExists()
    }
    @Test fun providerMessageIsLiteralDismissibleAndDoesNotDisposePlayback() {
        val state = mutableStateOf(DeviceManagementState(false, message = ManagementMessage("message-id", "<script>literal message</script>", System.currentTimeMillis() + 60_000)))
        var releases = 0
        compose.setContent {
            DeviceManagementGate(state.value, { state.value = state.value.copy(message = null) }) {
                DisposableEffect(Unit) { onDispose { releases++ } }; Text("Playing")
            }
        }
        compose.onNodeWithText("<script>literal message</script>").assertExists()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("<script>literal message</script>").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, releases) }
    }
}
