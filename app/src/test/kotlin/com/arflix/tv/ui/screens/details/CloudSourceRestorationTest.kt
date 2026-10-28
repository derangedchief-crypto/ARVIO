package com.arflix.tv.ui.screens.details

import org.junit.Assert.*
import org.junit.Test

class CloudSourceRestorationTest {
    @Test fun recoveredCloudRefreshesAnOpenSearch(){
        assertTrue(shouldRetryRestoredCloudSources(false,true,true))
    }
    @Test fun idleBrowsingInitialGrantAndRepeatedSignalsDoNotStartExtraRequests(){
        assertFalse(shouldRetryRestoredCloudSources(false,true,false))
        assertFalse(shouldRetryRestoredCloudSources(null,true,true))
        assertFalse(shouldRetryRestoredCloudSources(true,true,true))
        assertFalse(shouldRetryRestoredCloudSources(true,false,true))
    }
    @Test fun resumedSearchRetainsExistingSourcesAndChecksItsOriginalItem(){
        val source=java.io.File("src/main/kotlin/com/arflix/tv/ui/screens/details/DetailsViewModel.kt").readText()
        val retry=source.substringAfter("fun retrySourcesAfterCloudRestoration()").substringBefore("fun loadStreams(")
        assertTrue(retry.contains("request.type!=currentMediaType"))
        assertTrue(retry.contains("request.id!=currentMediaId"))
        assertTrue(retry.contains("preserveExisting=true"))
        assertTrue(source.contains("streams = retainedStreams"))
    }
}
