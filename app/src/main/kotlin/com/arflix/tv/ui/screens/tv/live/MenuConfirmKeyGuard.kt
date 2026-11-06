package com.arflix.tv.ui.screens.tv.live

import android.view.KeyEvent

/** A popup must see its own fresh press before accepting a confirm release.
 * The held key which opened it belongs to the previous focus target. */
internal class MenuConfirmKeyGuard {
    private var pressed: Int? = null

    fun consume(event: KeyEvent): Boolean {
        if (event.keyCode !in confirmKeys) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) true
                else { pressed = event.keyCode; false }
            }
            KeyEvent.ACTION_UP -> {
                val accepted = pressed == event.keyCode && !event.isCanceled
                pressed = null
                !accepted
            }
            else -> true
        }
    }

    private companion object {
        val confirmKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
    }
}
