package com.arflix.tv.ui.screens.xtreamgate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.AccentWhite
import com.arflix.tv.ui.theme.ArcticBlack
import com.arflix.tv.ui.theme.BorderLight
import com.arflix.tv.ui.theme.Cyan
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.Purple
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextTertiary
import com.arflix.tv.ui.theme.appBackgroundDark

/**
 * Mandatory Xtream login gate for Extreme TV Network. This is the very first screen
 * shown on launch until valid Xtream credentials are entered and saved — the user
 * cannot reach profile selection, home, or settings until this succeeds. The Xtream
 * host itself is fixed (see FIXED_XTREAM_HOST_URL in SettingsScreen.kt / the matching
 * constant in XtreamGateViewModel) and is never shown as an editable field here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamGateScreen(
    viewModel: XtreamGateViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var focusedField by remember { mutableStateOf("username") }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val buttonFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        usernameFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0A0A0F),
                        Color(0xFF0F172A),
                        Color(0xFF0A0A0F)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1F2937).copy(alpha = 0.25f),
                            Color.Transparent
                        ),
                        radius = 900f
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF151520))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Extreme TV Network",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(6.dp)

                Text(
                    text = "Sign in with your account to continue",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )

                Spacer(28.dp)

                if (uiState.errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF3D1515))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = uiState.errorMessage!!,
                            fontSize = 13.sp,
                            color = Color(0xFFEF4444)
                        )
                    }
                    Spacer(20.dp)
                }

                GateTextField(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = "Username",
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() }
                    ),
                    onRequestKeyboard = { keyboardController?.show() },
                    isFocused = focusedField == "username",
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(usernameFocusRequester)
                        .onFocusChanged { if (it.isFocused) focusedField = "username" }
                )

                Spacer(16.dp)

                GateTextField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChange,
                    placeholder = "Password",
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            buttonFocusRequester.requestFocus()
                        }
                    ),
                    onRequestKeyboard = { keyboardController?.show() },
                    isFocused = focusedField == "password",
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester)
                        .onFocusChanged { if (it.isFocused) focusedField = "password" }
                )

                Spacer(24.dp)

                GateButton(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.submit(onSuccess = onLoginSuccess)
                    },
                    text = when {
                        !uiState.isSubmitting -> "Sign In"
                        !uiState.progressText.isNullOrBlank() -> uiState.progressText!!
                        else -> "Signing in…"
                    },
                    isFocused = focusedField == "button",
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(buttonFocusRequester)
                        .onFocusChanged { if (it.isFocused) focusedField = "button" }
                )
            }
        }
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.height(height))
}

@Composable
private fun GateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onRequestKeyboard: () -> Unit = {},
    isPassword: Boolean = false,
    isFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val backgroundColor = appBackgroundDark().copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isFocused) {
                    Modifier.background(
                        Brush.linearGradient(colors = listOf(Cyan, Purple, Pink)),
                        RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier.background(BorderLight, RoundedCornerShape(12.dp))
                }
            )
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Normal
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = keyboardActions,
            singleLine = true,
            cursorBrush = SolidColor(Cyan),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, fontSize = 15.sp, color = TextTertiary)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun GateButton(
    onClick: () -> Unit,
    text: String,
    isFocused: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val focusedBackground = AccentWhite
    val focusedText = ArcticBlack
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isFocused) {
                    Modifier.background(focusedBackground)
                } else {
                    Modifier.background(Color.Black, RoundedCornerShape(12.dp))
                }
            )
            // Plain touch-clickable — androidx.tv.material3.Button's tap handling
            // is tuned for D-pad focus and can be unreliable on touch/mobile.
            .focusable(interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) focusedText else TextPrimary
        )
    }
}
