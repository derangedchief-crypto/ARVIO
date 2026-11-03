package com.arflix.tv.data.repository

import android.app.Application
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WatchHistoryTimestampTest {
    private val repository = WatchHistoryRepository(mockk(), mockk(), mockk(), mockk())

    @Test fun supabaseMicrosecondsAndEquivalentOffsetsKeepTheSameHistoryOrdering() {
        val expected = Instant.parse("2026-10-29T03:44:57.690Z").toEpochMilli()
        listOf(
            "2026-10-29T03:44:57.690579+00:00",
            "2026-10-29T03:44:57.690579Z",
            "2026-10-29T05:44:57.690579+02:00",
            "2026-10-28T22:14:57.690579-05:30",
        ).forEach { assertEquals(expected, repository.parseEpoch(it)) }
    }

    @Test fun missingAndInvalidDatesStillUseTheExistingFallback() {
        listOf(null, "", " ", "invalid").forEach { assertEquals(0L, repository.parseEpoch(it)) }
    }
}
