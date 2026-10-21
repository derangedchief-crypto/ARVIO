package com.arflix.tv.ui.screens.tv.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.koin.core.context.GlobalContext
import org.junit.Test
import org.junit.runner.RunWith

/** The production graph uses Android native SQLite and WorkManager. Probe it on
 * Android rather than leaving those background jobs alive in a JVM sandbox. */
@RunWith(AndroidJUnit4::class)
class OwnTvIptvRuntimeTest {
    @Test fun ownTvPlayerResolvesAndIsReusedWithoutStartingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val globalGraph = GlobalContext.getOrNull()
            val first = OwnTvIptvRuntime.player(context)
            assertSame(first, OwnTvIptvRuntime.player(context))
            assertFalse(first.hasActiveStream)
            assertSame(globalGraph, GlobalContext.getOrNull())
        }
    }
    @Test fun fastLiveEnginesAreRouteOwnedAndDoNotReplaceGlobalGraph() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val globalGraph = GlobalContext.getOrNull()
            val first = OwnTvIptvRuntime.livePlayer(context)
            val second = OwnTvIptvRuntime.livePlayer(context)
            assertNotSame(first, second)
            assertFalse(first.isPlaying.value)
            assertSame(globalGraph, GlobalContext.getOrNull())
            first.release(); second.release()
        }
    }
}
