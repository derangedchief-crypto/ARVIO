package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvChannel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class CategoryMenuInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<String>()
    private val hidden = mutableStateOf(emptySet<String>())
    private val channel = IptvChannel("list_1:1", "Channel", "https://example.test/live", "Provider News")
    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun content() {
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            val tree = buildCategoryTree(listOf(channel), emptySet(), emptySet(), hidden.value)
            fun record(action: String, playlist: String?, group: String) {
                assertEquals("list_1", playlist); assertEquals("Provider News", group)
                actions += action
            }
            Box(Modifier.requiredSize(900.dp, 600.dp)) {
                OwnTvCategoryPanel(tree, "all", expanded = true, listState = rememberLazyListState(),
                    onSelect = {}, onOpenSearch = {},
                    onHideCategory = { p, g -> record("hide", p, g); hidden.value = setOf("list_1|Provider News") },
                    onUnhideCategory = { p, g -> record("unhide", p, g); hidden.value = emptySet() },
                    onMoveCategoryUp = { p, g -> record("up", p, g) },
                    onMoveCategoryDown = { p, g -> record("down", p, g) },
                    onMoveCategoryToTop = { p, g -> record("top", p, g) })
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("Provider News").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitForIdle()
    }

    @Test fun openingKeyReleaseAndRepeatsDoNotHideButFreshConfirmDoes() {
        content(); open()
        compose.onNodeWithText(label(R.string.tv_stage_hide)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            view.dispatchKeyEvent(KeyEvent(0, 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 3))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        compose.onNodeWithText(label(R.string.tv_stage_hide)).assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("hide"), actions) }
    }

    @Test fun everyMoveActionTargetsTheSelectedProviderGroupAndCancelDoesNothing() {
        content()
        listOf(R.string.tv_stage_move_up to "up", R.string.tv_stage_move_top to "top", R.string.tv_stage_move_down to "down").forEach { (id, _) ->
            open(); compose.onNodeWithText(label(id)).performClick()
        }
        open(); compose.onNodeWithText(label(android.R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf("up", "top", "down"), actions) }
    }

    @Test fun lastVisibleCategoryCanBeRestoredThroughTheExistingMenu() {
        content(); open()
        compose.onNodeWithText(label(R.string.tv_stage_hide)).performClick()
        compose.onNodeWithText("Provider News").assertDoesNotExist()
        // Unhide belongs inside the long-press menu, not in the sidebar.
        compose.onNodeWithText(label(R.string.tv_stage_unhide)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.live_label_all_channels))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText(label(R.string.tv_stage_unhide)).performClick()
        compose.onNodeWithText("Provider News").performClick()
        compose.onNodeWithText("Provider News").assertExists()
        compose.onNodeWithText(label(R.string.tv_stage_unhide)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("hide", "unhide"), actions) }
    }

    @Test fun confirmGuardHandlesRemoteKeyboardAndGamepadKeys() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A).forEach { key ->
            val guard = MenuConfirmKeyGuard()
            assertTrue(guard.consume(KeyEvent(0, 100, KeyEvent.ACTION_DOWN, key, 2)))
            assertTrue(guard.consume(KeyEvent(KeyEvent.ACTION_UP, key)))
            assertFalse(guard.consume(KeyEvent(KeyEvent.ACTION_DOWN, key)))
            assertTrue(guard.consume(KeyEvent(0, 200, KeyEvent.ACTION_DOWN, key, 1)))
            assertFalse(guard.consume(KeyEvent(KeyEvent.ACTION_UP, key)))
            assertTrue(guard.consume(KeyEvent(KeyEvent.ACTION_UP, key)))
            assertFalse(guard.consume(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        }
    }
}
