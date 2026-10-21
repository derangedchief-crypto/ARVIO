package com.arflix.tv.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSourceLookupTest {
    @Test fun foregroundTakesOverCancelledPrefetchWithoutRestart() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope)
        val release = CompletableDeferred<Int>()
        var calls = 0
        val prefetch = async { lookup.get("account/movie") { calls++; release.await() } }
        runCurrent()
        prefetch.cancelAndJoin()
        advanceTimeBy(100)
        val foreground = async { lookup.get("account/movie") { calls++; 99 } }
        runCurrent()
        release.complete(42)
        assertEquals(42, foreground.await())
        assertEquals(1, calls)
    }

    @Test fun abandonedWorkIsCancelledAfterGrace() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope)
        var cancelled = false
        val request = async { lookup.get("account/movie") {
            try { awaitCancellation() } finally { cancelled = true }
        } }
        runCurrent()
        request.cancelAndJoin()
        assertFalse(cancelled)
        advanceTimeBy(501)
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun differentAccountsNeverShareResults() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope)
        val release = CompletableDeferred<Int>()
        val first = async { lookup.get("account-a/movie") { release.await() } }
        runCurrent()
        assertEquals(2, lookup.get("account-b/movie") { 2 })
        release.complete(1)
        assertEquals(1, first.await())
    }

    @Test fun cancellingOneCollectorDoesNotCancelAnother() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope)
        val release = CompletableDeferred<Int>()
        val first = async { lookup.get("movie") { release.await() } }
        runCurrent()
        val second = async { lookup.get("movie") { error("duplicate request") } }
        runCurrent()
        first.cancelAndJoin()
        advanceTimeBy(1000)
        release.complete(42)
        assertEquals(42, second.await())
    }

    @Test fun noCompletedReuseUnlessExplicitlyEnabled() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope)
        assertEquals(1, lookup.get("movie") { 1 })
        assertEquals(2, lookup.get("movie") { 2 })
    }

    @Test fun failedLookupIsRetriedNotCached() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val lookup = SharedSourceLookup<Int>(scope, completedTtlMs = 1000)
        assertTrue(runCatching { lookup.get("movie") { error("offline") } }.isFailure)
        runCurrent()
        assertEquals(42, lookup.get("movie") { 42 })
    }

    @Test fun completedResultExpires() = runTest {
        val lookup = SharedSourceLookup<Int>(backgroundScope, completedTtlMs = 1000, now = { testScheduler.currentTime })
        assertEquals(1, lookup.get("movie") { 1 })
        runCurrent()
        assertEquals(1, lookup.get("movie") { 2 })
        advanceTimeBy(1001)
        assertEquals(3, lookup.get("movie") { 3 })
    }
}
