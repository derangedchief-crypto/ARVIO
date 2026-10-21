package com.arflix.tv.ui.screens.xtreamgate

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class XtreamGateActivationInputTest {
    @get:Rule val compose = createComposeRule()
    private var submits = 0

    private fun mount(keyboard: Boolean = false) {
        compose.setContent {
            var state by remember { mutableStateOf(XtreamGateUiState()) }
            val input = LocalInputModeManager.current
            if (keyboard) SideEffect { input.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalInspectionMode provides true) {
                XtreamGateContent(state, onActivationCodeChange = { state = state.copy(activationCode = it) },
                    onSubmit = { submits++; state = state.copy(isSubmitting = true, progressText = "Signing in…") })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("123456")
    }

    @Test fun firstTouchOnActivateSubmitsWhileCodeFieldStillHasFocus() {
        mount()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Activate").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, submits) }
        compose.onNodeWithText("Signing in…").assertExists().assertIsNotEnabled()
    }

    @Test fun keyboardDoneSubmitsInsteadOfOnlyMovingFocusToActivate() {
        mount()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(1, submits) }
        compose.onNodeWithText("Signing in…").assertExists()
    }

    @Test fun firstDpadCenterOnFocusedActivateSubmitsWithoutAnExtraFocusableLayer() {
        mount(keyboard = true)
        compose.onNodeWithText("Activate").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Activate").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, submits) }
    }
}
