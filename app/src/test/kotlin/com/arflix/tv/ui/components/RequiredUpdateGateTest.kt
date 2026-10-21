package com.arflix.tv.ui.components

import android.app.Application
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.arflix.tv.updater.AppUpdate
import com.arflix.tv.updater.RequiredUpdateState
import com.arflix.tv.updater.UpdateStatus
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
class RequiredUpdateGateTest {
    @get:Rule val compose = createComposeRule()
    private val update = AppUpdate("2.0.103", "Update", "", null, "test.apk", "https://example.test/test.apk", 100)

    @Test fun incomingUpdateDisposesPlaybackAndBackCannotExposeContent() {
        val state = mutableStateOf(RequiredUpdateState(checking = false))
        var disposed = 0
        var downloads = 0
        var owner: OnBackPressedDispatcherOwner? = null
        compose.setContent {
            owner = LocalOnBackPressedDispatcherOwner.current
            RequiredUpdateGate(state.value, UpdateStatus.UpdateAvailable(update), { downloads++ }, {}, {}) {
                DisposableEffect(Unit) { onDispose { disposed++ } }
                Text("Playback and app navigation")
            }
        }
        compose.onNodeWithText("Playback and app navigation").assertExists()
        compose.runOnIdle { state.value = RequiredUpdateState(checking = false, update = update) }
        compose.onNodeWithText("Update required").assertExists()
        compose.onNodeWithText("Playback and app navigation").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, disposed)
            assertNotNull(owner)
            owner!!.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("Update required").assertExists()
        compose.onNodeWithText("Update now").performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        compose.onNodeWithText("Ignore").assertDoesNotExist()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }

    @Test fun launchCheckDoesNotComposePlayback() {
        compose.setContent {
            RequiredUpdateGate(RequiredUpdateState(), UpdateStatus.Idle, {}, {}, {}) { Text("Player") }
        }
        compose.onNodeWithText("Checking for updates").assertExists()
        compose.onNodeWithText("Player").assertDoesNotExist()
    }

    @Test fun failedInstallRetainsGateAndOffersRetry() {
        var retries = 0
        compose.setContent {
            RequiredUpdateGate(RequiredUpdateState(checking = false, update = update),
                UpdateStatus.Failure("Installation cancelled", update), {}, { retries++ }, {}) { Text("Player") }
        }
        compose.onNodeWithText("Installation cancelled").assertExists()
        compose.onNodeWithText("Retry update").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Player").assertDoesNotExist()
    }
}
