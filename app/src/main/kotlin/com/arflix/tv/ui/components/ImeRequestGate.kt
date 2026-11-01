package com.arflix.tv.ui.components

/** Coalesce overlapping focus/recomposition/user callbacks for one editor. */
internal class ImeRequestGate(private val intervalMs: Long = 500) {
    private var lastRequest: Long? = null
    fun allow(attached: Boolean, windowFocused: Boolean, editorFocused: Boolean, nowMs: Long): Boolean {
        if(!attached||!windowFocused||!editorFocused)return false
        val previous=lastRequest
        if(previous!=null&&nowMs-previous<intervalMs)return false
        lastRequest=nowMs
        return true
    }
}
