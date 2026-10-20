package com.arflix.tv.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.arflix.tv.data.repository.MaintenanceState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaintenanceGateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enablingMaintenanceDisposesPlaybackAndRemovesEveryAppControl() {
        val state = mutableStateOf(MaintenanceState(checking = false))
        var playerMounted = false
        var releases = 0
        compose.setContent {
            MaintenanceGate(state.value) {
                DisposableEffect(Unit) {
                    playerMounted = true
                    onDispose { playerMounted = false; releases++ }
                }
                Text("Movie player and navigation")
            }
        }
        compose.onNodeWithText("Movie player and navigation").assertExists()
        compose.runOnIdle { state.value = MaintenanceState(active = true, message = "Scheduled update", checking = false) }
        compose.onNodeWithText("Under maintenance").assertExists()
        compose.onNodeWithText("Scheduled update").assertExists()
        compose.onNodeWithText("Movie player and navigation").assertDoesNotExist()
        compose.runOnIdle { assertFalse(playerMounted); assertEquals(1, releases) }
        compose.runOnIdle { state.value = MaintenanceState(checking = false) }
        compose.onNodeWithText("Under maintenance").assertDoesNotExist()
        compose.onNodeWithText("Movie player and navigation").assertExists()
    }

    @Test fun launchCheckAndCachedMaintenanceNeverMountNavigation() {
        val state = mutableStateOf(MaintenanceState())
        compose.setContent { MaintenanceGate(state.value) { Text("Home / Login / Profiles") } }
        compose.onNodeWithText("Home / Login / Profiles").assertDoesNotExist()
        compose.runOnIdle { state.value = MaintenanceState(active = true, connectionFailed = true) }
        compose.onNodeWithText("Under maintenance").assertExists()
        compose.onNodeWithText("Home / Login / Profiles").assertDoesNotExist()
        compose.onNodeWithText("Waiting for a connection to check maintenance status.").assertExists()
    }
}
