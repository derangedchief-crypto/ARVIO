package com.arflix.tv.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuideRefreshCoordinatorTest {
    @Test fun homeTvAndWorkerRequestsShareOneJob() = runTest {
        val coordinator = GuideRefreshCoordinator(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var imports = 0
        val home = coordinator.request { imports++; finish.await() }
        val tv = coordinator.request { imports++ }
        val worker = coordinator.request { imports++ }
        assertSame(home, tv)
        assertSame(home, worker)
        runCurrent()
        assertEquals(1, imports)
        finish.complete(Unit)
        home.join()
    }
    @Test fun leavingTvCancelsOnlyTheWaiterNotGuideLoading() = runTest {
        val coordinator = GuideRefreshCoordinator(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var complete = false
        val job = coordinator.request { finish.await(); complete = true }
        val tvScreen = launch { job.join() }
        runCurrent()
        tvScreen.cancelAndJoin()
        assertTrue(job.isActive)
        finish.complete(Unit)
        job.join()
        assertTrue(complete)
    }
    @Test fun laterScheduledRequestCanRunAfterCompletion() = runTest {
        val coordinator = GuideRefreshCoordinator(backgroundScope)
        var imports = 0
        coordinator.request { imports++ }.join()
        coordinator.request { imports++ }.join()
        assertEquals(2, imports)
    }
}
