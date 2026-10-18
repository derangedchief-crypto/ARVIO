package com.arflix.tv.ui.screens.xtreamgate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import coil.compose.AsyncImage

/**
 * Set to true to re-enable the "Skip for now" opt-out on the Xtream gate.
 *
 * Keep the entire skip block behind this flag rather than commenting out only the
 * label: a `Box` that still carries `Modifier.clickable` renders no content but is
 * still an invisible tap target on touch and still takes D-pad focus on TV, so the
 * user can trigger skip by accident and focus appears to vanish.
 */
private const val SHOW_SKIP_BUTTON = false

/**
 * Mandatory Xtream login step for Extreme TV Network — the first of the two
 * onboarding screens shown on first launch (Xtream, then Jellyfin). The user signs in
 * here and then proceeds to the Jellyfin step. The Xtream host itself is fixed (see
 * FIXED_XTREAM_HOST_URL in SettingsScreen.kt / the matching constant in
 * XtreamGateViewModel) and is never shown as an editable field here.
 *
 * The "Skip for now" opt-out is currently disabled via [SHOW_SKIP_BUTTON]; the
 * [onSkip] callback is kept so the navigation graph wiring stays intact if the
 * opt-out is restored. Note that hiding the button is not enforcement — verify that
 * BACK press and any other caller of [XtreamGateViewModel.skip] cannot bypass the
 * gate either.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamGateScreen(
    viewModel: XtreamGateViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {},
    onSkip: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var focusedField by remember { mutableStateOf("activation") }
    val activationFocusRequester = remember { FocusRequester() }
    val buttonFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        activationFocusRequester.requestFocus()
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
        PosterWallBackground()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xE60A2137),
                            Color(0xF20A1424),
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
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF151520))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Cyan, Purple, Pink)))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "EXTREME TV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ArcticBlack,
                    )
                }

                Spacer(18.dp)

                Text(
                    text = "Activate your TV",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(10.dp)

                Text(
                    text = if (SHOW_SKIP_BUTTON) {
                        "Enter the six-digit code supplied with your subscription, or skip for now."
                    } else {
                        "Enter the six-digit code supplied with your subscription to continue."
                    },
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.64f),
                    modifier = Modifier.fillMaxWidth(),
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
                    value = uiState.activationCode,
                    onValueChange = viewModel::onActivationCodeChange,
                    placeholder = "• • • • • •",
                    keyboardType = KeyboardType.Number,
                    isActivationCode = true,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            buttonFocusRequester.requestFocus()
                        }
                    ),
                    onRequestKeyboard = { keyboardController?.show() },
                    isFocused = focusedField == "activation",
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(activationFocusRequester)
                        .onFocusChanged { if (it.isFocused) focusedField = "activation" }
                )

                Spacer(24.dp)

                GateButton(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.submit(onSuccess = onLoginSuccess)
                    },
                    text = when {
                        !uiState.isSubmitting -> "Activate"
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

                Spacer(18.dp)
                Text(
                    text = "Your code connects this device to your existing subscription.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.42f),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Skip opt-out: fully removed from the layout (no spacer, no invisible
                // hit target, no focusable node) while SHOW_SKIP_BUTTON is false.
                if (SHOW_SKIP_BUTTON) {
                    Spacer(16.dp)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !uiState.isSubmitting) {
                                keyboardController?.hide()
                                viewModel.skip(onSkip = onSkip)
                            }
                            .padding(vertical = 8.dp, horizontal = 12.dp)
                    ) {
                        Text(
                            text = "Skip for now",
                            fontSize = 13.sp,
                            color = Color.White.copy(
                                alpha = if (uiState.isSubmitting) 0.3f else 0.55f
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.height(height))
}

@Composable
internal fun GateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onRequestKeyboard: () -> Unit = {},
    isPassword: Boolean = false,
    isActivationCode: Boolean = false,
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
                fontSize = if (isActivationCode) 23.sp else 15.sp,
                color = TextPrimary,
                fontWeight = if (isActivationCode) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (isActivationCode) 5.sp else 0.sp,
                textAlign = if (isActivationCode) TextAlign.Center else TextAlign.Start,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = keyboardActions,
            singleLine = true,
            cursorBrush = SolidColor(Cyan),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (isActivationCode) Alignment.Center else Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = if (isActivationCode) 20.sp else 15.sp,
                            color = TextTertiary,
                            textAlign = if (isActivationCode) TextAlign.Center else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/** A muted, resilient poster wall: artwork is decorative and login stays usable
 * if the device is offline or any image is unavailable. */
@Composable
private fun PosterWallBackground() {
    val posters = remember {
        listOf(
            "https://image.tmdb.org/t/p/w342/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg",
            "https://image.tmdb.org/t/p/w342/3bhkrj58Vtu7enYsRolD1fZdja1.jpg",
            "https://image.tmdb.org/t/p/w342/8UlWHLMpgZm9bx6QYh0NFoq67TZ.jpg",
            "https://image.tmdb.org/t/p/w342/6oom5QYQ2yQTMJIbnvbkBL9cHo6.jpg",
            "https://image.tmdb.org/t/p/w342/uC6TTUhPpQCmgldGyYveKRAu8JN.jpg",
            "https://image.tmdb.org/t/p/w342/3Kblg8qOQbrxZ0p4HjaO0lyDqSM.jpg",
            "https://image.tmdb.org/t/p/w342/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg",
            "https://image.tmdb.org/t/p/w342/5KCVkau1HEl7ZzfPsKAPM0sMiKc.jpg",
            "https://image.tmdb.org/t/p/w342/6t8ESsNqii0z69lU6u1063ZwnN3.jpg",
            "https://image.tmdb.org/t/p/w342/74hLDKjD5aGYOotO6esUVaeISa2.jpg",
            "https://image.tmdb.org/t/p/w342/5J89gAtd1r1WbAiVtYqXcJ3M3nS.jpg",
            "https://image.tmdb.org/t/p/w342/2cal3GZ2s5UO1D8wJgY4K1v2r1I.jpg",
            "https://image.tmdb.org/t/p/w342/7WsyChQLEftFiDOVTGkv3hFpyyt.jpg",
            "https://image.tmdb.org/t/p/w342/9O7gLzmreU0nGkIB6K3BsJbzvNv.jpg",
            "https://image.tmdb.org/t/p/w342/4qfh0t9WlK31o3HqPdP1vP5gR5.jpg",
        )
    }
    val columns = remember(posters) { posters.chunked(3) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = 0.48f),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        columns.forEachIndexed { index, column ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer(
                        rotationZ = if (index % 2 == 0) -3f else 3f,
                        translationY = if (index % 2 == 0) -42f else 24f,
                    ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                column.forEach { poster ->
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }
}

@Composable
internal fun GateButton(
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
