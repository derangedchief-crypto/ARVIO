package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.IptvProgram
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tv.own.owntv.core.parser.XmltvParser

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class StreamingGuideIndexWriterTest {
    @Test fun upstreamXmltvParserPersistsWholeFeedWithoutRetainingWholeFeed() = runTest {
        val sizes = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        val writer = StreamingGuideIndexWriter { batch ->
            sizes += batch.values.sumOf { it.upcoming.size }
            seen += batch.keys
        }
        val xml = buildString {
            append("<tv>")
            repeat(1000) { i -> append("<programme channel=\"c$i\" start=\"20261019080000 +0000\" stop=\"20261019090000 +0000\"><title>Programme $i</title></programme>") }
            append("</tv>")
        }
        XmltvParser.parse(xml.byteInputStream(), onChannel = { _, _, _ -> },
            onProgramme = { id, start, end, title, description, _ ->
                writer.add(id, IptvProgram(title, description, start, end))
            })
        writer.flush()
        assertEquals(1000, seen.size)
        assertEquals(listOf(256, 256, 256, 232), sizes)
    }
    @Test fun flushKeepsInterruptedFinalBatchAndDoesNotWriteItTwice() {
        var written = 0
        val writer = StreamingGuideIndexWriter { batch -> written += batch.values.sumOf { it.upcoming.size } }
        writer.add("unselected", IptvProgram("Saved", startUtcMillis = 100, endUtcMillis = 200))
        writer.flush()
        writer.flush()
        assertEquals(1, written)
    }
}
