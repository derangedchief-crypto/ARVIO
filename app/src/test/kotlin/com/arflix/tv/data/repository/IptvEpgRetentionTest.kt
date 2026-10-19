package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class IptvEpgRetentionTest {
    private lateinit var index: IptvEpgIndex
    private val now = System.currentTimeMillis()
    private fun programme(title: String, offset: Long = 0) = IptvProgram(title,
        startUtcMillis = now + offset, endUtcMillis = now + offset + 3_600_000)
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("arvio_iptv_epg_index.db")
        index = IptvEpgIndex(RuntimeEnvironment.getApplication())
    }
    @After fun close() { index.close() }

    @Test fun shortRefreshPreservesOtherChannelAndLaterProgrammes() {
        val live = programme("Now")
        val later = programme("Later", 7_200_000)
        val other = programme("Other channel")
        index.replaceAll("source", mapOf("a" to IptvNowNext(now = live, upcoming = listOf(later)),
            "b" to IptvNowNext(now = other)), now)
        index.mergeChannels("source", mapOf("a" to IptvNowNext(now = live.copy(title = "Corrected now"))), now)
        val rows = index.loadWindow("source", setOf("a", "b"), now - 1, now + 14_400_000)
        assertEquals(listOf("Corrected now", "Later"), rows.getValue("a").map { it.title })
        assertEquals(listOf(other), rows.getValue("b"))
    }

    @Test fun repeatedRefreshReplacesSameStartWithoutGrowingDuplicates() {
        index.mergeChannels("source", mapOf("a" to IptvNowNext(now = programme("Old"))), now)
        repeat(3) { index.mergeChannels("source", mapOf("a" to IptvNowNext(now = programme("New"))), now) }
        assertEquals(1, index.countPrograms("source"))
        assertEquals("New", index.loadWindow("source", setOf("a"), now - 1, now + 7_200_000).getValue("a").single().title)
    }

    @Test fun interruptedRefreshAndReopenKeepLoadedGuide() {
        val saved = programme("Persisted programme")
        index.mergeChannels("source", mapOf("b" to IptvNowNext(now = saved)), now)
        index.beginRefresh("source", now)
        index.mergeChannels("source", mapOf("a" to IptvNowNext(now = programme("Partial"))), now)
        index.markRefreshFailed("source")
        index.close()
        index = IptvEpgIndex(RuntimeEnvironment.getApplication())
        assertEquals(saved, index.loadWindow("source", setOf("b"), now - 1, now + 7_200_000).getValue("b").single())
    }

    @Test fun differentSourcesNeverShareGuideRows() {
        index.mergeChannels("first", mapOf("a" to IptvNowNext(now = programme("First"))), now)
        index.mergeChannels("second", mapOf("a" to IptvNowNext(now = programme("Second"))), now)
        assertEquals("First", index.loadWindow("first", setOf("a"), now - 1, now + 7_200_000).getValue("a").single().title)
    }

    @Test fun mergingPrunesExpiredHistoryButNotFutureSchedule() {
        index.mergeChannels("source", mapOf("a" to IptvNowNext(
            recent = listOf(programme("Expired", -4 * 86_400_000L)),
            upcoming = listOf(programme("Tomorrow", 86_400_000L)))), now)
        assertEquals(1, index.countPrograms("source"))
    }
}
