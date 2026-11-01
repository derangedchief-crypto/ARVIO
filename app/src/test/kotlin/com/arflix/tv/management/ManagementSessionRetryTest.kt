package com.arflix.tv.management

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagementSessionRetryTest {
    @Test fun transientStateFailureRetriesWithoutProcessRestartOrAccountEmission()=runTest {
        var attempts=0;var failures=0
        val job=launch {
            runManagementSessionWithRetry({failures++},{delay(15_000)}) {
                attempts++
                if(attempts==1)error("synthetic transient keystore error")
                awaitCancellation()
            }
        }
        runCurrent();assertEquals(1,attempts);assertEquals(1,failures)
        advanceTimeBy(15_000);runCurrent();assertEquals(2,attempts);assertEquals(1,failures)
        job.cancel();runCurrent();advanceTimeBy(60_000);runCurrent();assertEquals(2,attempts)
    }

    @Test fun cancellationIsNotReportedOrRetried()=runTest {
        var retries=0;var failures=0
        try {
            runManagementSessionWithRetry({failures++},{retries++}) { throw CancellationException("lifecycle stopped") }
            fail("Cancellation swallowed")
        }catch(_:CancellationException){}
        assertEquals(0,retries);assertEquals(0,failures)
    }

    @Test fun completedSessionDoesNotSpinOrRetry()=runTest {
        var attempts=0
        runManagementSessionWithRetry({fail("Unexpected failure")},{fail("Unexpected retry")}) { attempts++ }
        assertEquals(1,attempts)
    }

    @Test fun retryRetainsObservedRestrictionsEvenWhenTheirDiskWriteFailed() {
        val saved=ManagementSession("a".repeat(64),"b".repeat(32),policy="active")
        for(policy in listOf("blocked","suspended","removed")){
            val next=preserveObservedManagementRestriction(saved,policy)
            assertEquals(policy,next.policy);assertEquals(saved.installationId,next.installationId)
        }
        val blocked=saved.copy(policy="blocked")
        assertEquals(blocked,preserveObservedManagementRestriction(blocked,"active"))
        assertEquals(blocked,preserveObservedManagementRestriction(blocked,null))
    }
}
