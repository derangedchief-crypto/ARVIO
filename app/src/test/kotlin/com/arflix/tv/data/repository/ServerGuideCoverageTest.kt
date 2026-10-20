package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class ServerGuideCoverageTest {
    @Test fun mostlyExpiredLiveServerCacheIsNotACompleteGuide() {
        assertTrue(needsServerGuideBulkRecovery(58, 4017))
    }
    @Test fun smallNumberOfUnpublishedOrExpiredChannelsDoesNotStartAnotherHugePull() {
        assertFalse(needsServerGuideBulkRecovery(4000, 0))
        assertFalse(needsServerGuideBulkRecovery(4000, 20))
        assertFalse(needsServerGuideBulkRecovery(1, 7))
    }
    @Test fun completelyExpiredKnownGuideRequiresBulkFallback() {
        assertTrue(needsServerGuideBulkRecovery(0, 1000))
    }
}
