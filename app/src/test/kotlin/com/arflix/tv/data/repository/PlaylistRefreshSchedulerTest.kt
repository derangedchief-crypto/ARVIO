package com.arflix.tv.data.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistRefreshSchedulerTest {
    @Test fun repeatedStartsKeepOneFiveMinuteLoop() = runTest {
        val scheduler = PlaylistRefreshScheduler(backgroundScope)
        var calls = 0
        val refresh = { calls++; Job().apply { complete() } }
        repeat(3) { scheduler.start(refresh) }
        runCurrent()
        advanceTimeBy(299_999); runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun slowRefreshDoesNotQueueOverlappingChecks() = runTest {
        val scheduler = PlaylistRefreshScheduler(backgroundScope)
        val coordinator = GuideRefreshCoordinator(backgroundScope)
        var calls = 0
        val pending = Job()
        scheduler.start { coordinator.request { calls++; pending.join() } }
        runCurrent()
        advanceTimeBy(300_000); runCurrent()
        advanceTimeBy(900_000); runCurrent()
        assertEquals(1, calls)
        pending.complete(); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, calls)
    }
}
