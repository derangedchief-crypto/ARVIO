package com.arflix.tv.ui.screens.tv.live

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep consuming the held press after fullscreen opens, including its release. */
internal class LivePreviewBackPress(private val scope: CoroutineScope, private val holdMs: Long = 600L) {
    private var held = false
    private var longPress = false
    private var job: Job? = null
    private var enabled: () -> Boolean = { false }
    private var shortAction: () -> Unit = {}

    fun down(isEnabled: () -> Boolean, onShort: () -> Unit, onLong: () -> Unit): Boolean {
        if (held) return true // native repeat events must not navigate twice
        if (!isEnabled()) return false
        held = true; longPress = false; enabled = isEnabled; shortAction = onShort
        job = scope.launch {
            delay(holdMs)
            longPress = true
            if (isEnabled()) onLong()
        }
        return true
    }

    fun up(cancelled: Boolean = false): Boolean {
        if (!held) return false
        job?.cancel(); job = null; held = false
        if (!cancelled && !longPress && enabled()) shortAction()
        longPress = false
        return true
    }

    fun cancel() { job?.cancel(); job = null; held = false; longPress = false }
}

/** A fullscreen key-down already navigated. Its release must not navigate the
 * newly revealed guide a second time or race disposal with the surface handoff. */
internal class LiveBackReleaseGuard {
    private var pending = false
    fun handledDown() { pending = true }
    fun consumeDown(repeat: Boolean): Boolean {
        if (!pending) return false
        if (repeat) return true
        pending = false
        return false
    }
    fun consumeUp(): Boolean = pending.also { pending = false }
}

@Composable
internal fun rememberLivePreviewBackShortcut(
    enabled: Boolean, onShortBack: () -> Unit, onLongBack: () -> Unit,
): (KeyEvent) -> Boolean {
    val scope = rememberCoroutineScope()
    val press = remember(scope) { LivePreviewBackPress(scope) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentShort by rememberUpdatedState(onShortBack)
    val currentLong by rememberUpdatedState(onLongBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(press, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) press.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); press.cancel() }
    }
    return remember(press) {
        { event ->
            if (event.key != Key.Back) false else when (event.type) {
                KeyEventType.KeyDown -> press.down({ currentEnabled }, { currentShort() }, { currentLong() })
                KeyEventType.KeyUp -> press.up(event.nativeKeyEvent.isCanceled)
                else -> false
            }
        }
    }
}
