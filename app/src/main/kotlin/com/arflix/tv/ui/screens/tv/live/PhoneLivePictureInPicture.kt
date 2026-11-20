package com.arflix.tv.ui.screens.tv.live

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

internal fun phoneLivePipAllowed(touchDevice: Boolean, supported: Boolean,
    hasPlayback: Boolean, playing: Boolean, authorized: Boolean): Boolean =
    touchDevice && supported && hasPlayback && playing && authorized

internal fun livePipParams(autoEnter: Boolean, source: Rect? = null): PictureInPictureParams =
    PictureInPictureParams.Builder().apply {
        setAspectRatio(Rational(16, 9))
        if (source != null && !source.isEmpty) setSourceRectHint(source)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setAutoEnterEnabled(autoEnter)
            setSeamlessResizeEnabled(true)
        }
    }.build()

/** Only mounted by the active Live TV screen. Android owns window movement/resize. */
@Composable
internal fun PhoneLivePictureInPicture(
    activity: Activity?, enabled: Boolean, playerView: View?, onModeChanged: (Boolean) -> Unit,
) {
    val host = activity as? ComponentActivity ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        !host.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    val currentEnabled = rememberUpdatedState(enabled)
    val currentView = rememberUpdatedState(playerView)
    val currentChanged = rememberUpdatedState(onModeChanged)
    fun params(autoEnter: Boolean): PictureInPictureParams {
        val bounds = Rect().takeIf { currentView.value?.getGlobalVisibleRect(it) == true }
        return livePipParams(autoEnter, bounds)
    }
    DisposableEffect(host) {
        val listener = Consumer<PictureInPictureModeChangedInfo> {
            currentChanged.value(it.isInPictureInPictureMode)
        }
        val leave = Runnable {
            // Android 12+ handles the Home gesture automatically; older versions
            // need the activity's user-leave callback (not onPause).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && currentEnabled.value &&
                !host.isFinishing && !host.isDestroyed && !host.isInPictureInPictureMode) {
                runCatching { host.enterPictureInPictureMode(params(false)) }
            }
        }
        host.addOnPictureInPictureModeChangedListener(listener)
        host.addOnUserLeaveHintListener(leave)
        currentChanged.value(host.isInPictureInPictureMode)
        onDispose {
            host.removeOnPictureInPictureModeChangedListener(listener)
            host.removeOnUserLeaveHintListener(leave)
            // Navigating away must not shrink a settings/activation screen later.
            runCatching { host.setPictureInPictureParams(livePipParams(false)) }
        }
    }
    SideEffect {
        runCatching { host.setPictureInPictureParams(params(enabled)) }
    }
}
