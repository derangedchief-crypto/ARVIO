package com.arflix.tv.ui.screens.tv.live

internal enum class OwnTvSurfaceEngine { NONE, FAST_LIVE, MPV }

/** Main-thread ownership of the requested view and the surface actually bound.
 * Callbacks already queued by a removed fullscreen view must not take ownership
 * back from its replacement. Never call both engines for the same lifecycle event. */
internal class OwnTvSurfaceHandoff<H : Any, S : Any>(
    private val valid: (S) -> Boolean,
    private val attach: (OwnTvSurfaceEngine, H, S) -> Unit,
    private val detach: (OwnTvSurfaceEngine, S) -> Unit,
    private val resize: (OwnTvSurfaceEngine, Int, Int) -> Unit,
) {
    private var requested: H? = null
    private var holder: H? = null
    private var surface: S? = null
    private var desired = OwnTvSurfaceEngine.NONE
    private var owner = OwnTvSurfaceEngine.NONE

    fun selectEngine(engine: OwnTvSurfaceEngine) {
        if (desired == engine) return
        detachOwner()
        desired = engine
        val h = holder; val s = surface
        if (h != null && s != null) bind(h, s)
    }

    fun request(h: H, s: S) {
        requested = h
        if (valid(s)) bind(h, s)
    }

    fun created(h: H, s: S) {
        if (h === requested && valid(s)) bind(h, s)
    }

    fun changed(h: H, width: Int, height: Int) {
        if (h === holder && owner != OwnTvSurfaceEngine.NONE && width > 0 && height > 0) resize(owner, width, height)
    }

    fun destroyed(h: H) {
        if (h !== holder) return
        detachOwner(); holder = null; surface = null
    }

    fun remove(h: H) {
        if (h === requested) requested = null
        destroyed(h)
    }

    fun clear() {
        detachOwner(); requested = null; holder = null; surface = null; desired = OwnTvSurfaceEngine.NONE
    }

    private fun bind(h: H, s: S) {
        if (h === holder && s === surface && owner == desired) return
        // Within one engine, binding the new surface replaces its old target.
        // Do not clear the old target afterwards: that can tear down the new one.
        holder = h; surface = s
        if (desired != OwnTvSurfaceEngine.NONE && valid(s)) {
            attach(desired, h, s)
            owner = desired
        } else owner = OwnTvSurfaceEngine.NONE
    }

    private fun detachOwner() {
        surface?.let { if (owner != OwnTvSurfaceEngine.NONE) detach(owner, it) }
        owner = OwnTvSurfaceEngine.NONE
    }
}
