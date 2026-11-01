package com.arflix.tv.ui.components

import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import java.util.WeakHashMap

// All access is posted to the view's UI thread. Weak keys do not retain dialogs.
private val imeRequests=WeakHashMap<View,ImeRequestGate>()

internal fun requestImeOnce(view: View, requestFocus: Boolean = false, show: () -> Unit) {
    view.post {
        if(!view.isAttachedToWindow||!view.hasWindowFocus())return@post
        if(requestFocus)view.requestFocus()
        val gate=imeRequests.getOrPut(view){ImeRequestGate()}
        if(gate.allow(view.isAttachedToWindow,view.hasWindowFocus(),view.hasFocus(),SystemClock.elapsedRealtime()))show()
    }
}

internal fun showEditorKeyboard(view: View) = requestImeOnce(view,requestFocus=true) {
    val manager=view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    // SHOW_FORCED fallback used to issue a second synchronous Binder call when
    // the first was merely pending. It can also keep TV IMEs open after dismissal.
    manager?.showSoftInput(view,InputMethodManager.SHOW_IMPLICIT)
    Unit
}
