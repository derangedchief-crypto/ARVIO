package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.koin.core.context.GlobalContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Verify the real dependency graph resolves before a device ever attempts a native stream. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class OwnTvIptvRuntimeTest {
    @Test fun ownTvPlayerResolvesAndIsReusedWithoutStartingPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val globalGraph = GlobalContext.getOrNull()
        val first = OwnTvIptvRuntime.player(context)
        assertSame(first, OwnTvIptvRuntime.player(context))
        assertFalse(first.hasActiveStream)
        assertSame(globalGraph, GlobalContext.getOrNull())
    }
}
