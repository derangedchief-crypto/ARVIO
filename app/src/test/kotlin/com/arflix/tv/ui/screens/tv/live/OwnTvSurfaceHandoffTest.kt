package com.arflix.tv.ui.screens.tv.live

import org.junit.Assert.*
import org.junit.Test

class OwnTvSurfaceHandoffTest {
    private class Holder(val name: String)
    private class Surface(val name: String, var valid: Boolean = true)
    private val operations = mutableListOf<String>()
    private val validityChecks = mutableListOf<String>()
    private val handoff = OwnTvSurfaceHandoff<Holder, Surface>(
        valid = { validityChecks += it.name; it.valid },
        attach = { engine, _, surface -> operations += "attach:$engine:${surface.name}" },
        detach = { engine, surface -> operations += "detach:$engine:${surface.name}" },
        resize = { engine, width, height -> if (engine == OwnTvSurfaceEngine.MPV) operations += "resize:$width:$height" },
    )

    @Test fun previewFullscreenPreviewNeverCallsInactiveMpvOrAcceptsOldViewCallbacks() {
        val mini = Holder("mini"); val full = Holder("full")
        val miniSurface = Surface("mini"); val fullSurface = Surface("full")
        handoff.request(mini, miniSurface)
        assertTrue(operations.isEmpty()) // idle view must not initialize mpv
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        handoff.request(full, fullSurface)
        handoff.request(mini, miniSurface)
        // Framework callbacks can already be queued when removeCallback runs.
        val checksBeforeOldCallbacks = validityChecks.size
        handoff.created(full, fullSurface)
        handoff.changed(full, 1920, 1080)
        handoff.destroyed(full)
        handoff.remove(full)
        assertEquals(checksBeforeOldCallbacks, validityChecks.size)
        assertEquals(listOf("attach:FAST_LIVE:mini", "attach:FAST_LIVE:full", "attach:FAST_LIVE:mini"), operations)
        handoff.destroyed(mini)
        assertEquals("detach:FAST_LIVE:mini", operations.last())
        assertFalse(operations.any { "MPV" in it })
    }

    @Test fun repeatedRoundTripsDoNotClearTheReplacementSurface() {
        val mini = Holder("mini"); val full = Holder("full")
        val miniSurface = Surface("mini"); val fullSurface = Surface("full")
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        handoff.request(mini, miniSurface)
        repeat(10) {
            handoff.request(full, fullSurface)
            handoff.created(mini, miniSurface); handoff.destroyed(mini)
            handoff.request(mini, miniSurface)
            handoff.created(full, fullSurface); handoff.destroyed(full)
        }
        assertEquals(21, operations.size)
        assertTrue(operations.all { it.startsWith("attach:FAST_LIVE:") })
    }

    @Test fun replacementNotReadyKeepsOldSurfaceUntilItReallyDiesThenBindsNewOne() {
        val old = Holder("old"); val next = Holder("next")
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        handoff.request(old, Surface("old"))
        handoff.request(next, Surface("pending", valid = false))
        assertEquals(1, operations.size)
        handoff.destroyed(old)
        handoff.created(next, Surface("next"))
        assertEquals(listOf("attach:FAST_LIVE:old", "detach:FAST_LIVE:old", "attach:FAST_LIVE:next"), operations)
    }

    @Test fun actualEngineHandoverDetachesOnlyTheOldOwnerThenBindsTheNewOwner() {
        val holder = Holder("view"); val surface = Surface("surface")
        handoff.request(holder, surface)
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        handoff.selectEngine(OwnTvSurfaceEngine.MPV)
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        assertEquals(listOf("attach:FAST_LIVE:surface", "detach:FAST_LIVE:surface", "attach:MPV:surface",
            "detach:MPV:surface", "attach:FAST_LIVE:surface"), operations)
    }

    @Test fun staleSurfaceSizesCannotResizeActiveMpvAndStopCannotReviveIt() {
        val old = Holder("old"); val next = Holder("next")
        handoff.selectEngine(OwnTvSurfaceEngine.MPV)
        handoff.request(old, Surface("old")); handoff.request(next, Surface("next"))
        handoff.changed(old, 3840, 2160)
        handoff.changed(next, 960, 540)
        handoff.selectEngine(OwnTvSurfaceEngine.NONE)
        handoff.created(next, Surface("new-frame")); handoff.changed(next, 1280, 720)
        assertEquals(listOf("attach:MPV:old", "attach:MPV:next", "resize:960:540", "detach:MPV:next"), operations)
    }

    @Test fun releaseIgnoresEveryLateCallbackAndSurfaceCanRecreateWhileStillOwned() {
        val holder = Holder("view")
        handoff.selectEngine(OwnTvSurfaceEngine.FAST_LIVE)
        handoff.request(holder, Surface("first")); handoff.destroyed(holder)
        handoff.created(holder, Surface("replacement"))
        assertEquals("attach:FAST_LIVE:replacement", operations.last())
        handoff.clear()
        val count = operations.size
        handoff.created(holder, Surface("late")); handoff.changed(holder, 800, 450); handoff.destroyed(holder)
        assertEquals(count, operations.size)
    }
}
