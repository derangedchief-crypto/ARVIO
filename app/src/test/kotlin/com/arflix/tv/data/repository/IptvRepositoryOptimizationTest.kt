package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvNowNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class IptvRepositoryOptimizationTest {

    // Helper to instantiate private class BackslashEscapeSanitizingInputStream using reflection
    private fun createSanitizingStream(input: InputStream): InputStream {
        val clazz = Class.forName("com.arflix.tv.data.repository.IptvRepository\$BackslashEscapeSanitizingInputStream")
        val constructor = clazz.getDeclaredConstructor(InputStream::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(input) as InputStream
    }

    @Test
    fun testBackslashSanitizationBulkRead() {
        // \" -> "
        // \n -> newline
        // control chars (0x01) -> space (0x20)
        // regular chars not escaped (e.g. \y) -> keep y
        val inputStr = "Hello\\\"World\\nTest\\yDone\u0001Control"
        val expectedStr = "Hello\"World\nTestyDone Control"

        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)

        val buffer = ByteArray(100)
        val readCount = sanitizingStream.read(buffer, 0, buffer.size)

        val result = String(buffer, 0, readCount, Charsets.UTF_8)
        assertEquals(expectedStr, result)
    }

    @Test
    fun testBackslashSanitizationByteByByteRead() {
        val inputStr = "Hello\\\"World\\nTest\\yDone\u0001Control"
        val expectedStr = "Hello\"World\nTestyDone Control"

        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)

        val sb = StringBuilder()
        while (true) {
            val b = sanitizingStream.read()
            if (b == -1) break
            sb.append(b.toChar())
        }

        assertEquals(expectedStr, sb.toString())
    }

    @Test
    fun testBackslashSanitizationBoundary() {
        // Backslash at the boundary of buffer size
        val inputStr = "A\\nB" // length 4
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)

        // Read exactly up to the backslash first (2 bytes: 'A', '\')
        // Actually, let's read with buffer size 2
        val buffer = ByteArray(2)
        val read1 = sanitizingStream.read(buffer, 0, 2)
        assertEquals(2, read1)
        // Since '\' is followed by 'n', our stream reads lookahead 'n' from the underlying stream,
        // maps it to '\n' and puts it into buffer[1]. So buffer should have ['A', '\n'].
        assertEquals('A', buffer[0].toInt().toChar())
        assertEquals('\n', buffer[1].toInt().toChar())

        // Next read should get the remaining 'B'
        val read2 = sanitizingStream.read(buffer, 0, 2)
        assertEquals(1, read2)
        assertEquals('B', buffer[0].toInt().toChar())
    }

    @Test
    fun testBackslashSanitizationTrailingBackslash() {
        val inputStr = "A\\" // Traling backslash
        val rawStream = ByteArrayInputStream(inputStr.toByteArray(Charsets.UTF_8))
        val sanitizingStream = createSanitizingStream(rawStream)

        val buffer = ByteArray(5)
        val read = sanitizingStream.read(buffer, 0, 5)
        assertEquals(2, read)
        assertEquals('A', buffer[0].toInt().toChar())
        assertEquals('\\', buffer[1].toInt().toChar())
    }

    @Test
    fun testConcurrentPlaylistLoadThreadSafety() = runBlocking(Dispatchers.Default) {
        // Simulate synchronized list modification and copying concurrently
        val list = Collections.synchronizedList(mutableListOf<Int>())
        val exceptionCount = AtomicInteger(0)

        // Coroutines writing to the list and reading from it (calling toList())
        val writers = (1..10).map { id ->
            async {
                repeat(1000) {
                    list.add(it)
                }
            }
        }

        val readers = (1..10).map {
            async {
                repeat(1000) {
                    try {
                        // This must be synchronized to prevent ConcurrentModificationException
                        val currentCopy = synchronized(list) { list.toList() }
                        // Use the copy to prevent compiler optimizations dropping it
                        if (currentCopy.size < 0) {
                            fail()
                        }
                    } catch (e: ConcurrentModificationException) {
                        exceptionCount.incrementAndGet()
                    }
                }
            }
        }

        writers.awaitAll()
        readers.awaitAll()

        assertEquals("Should not encounter any ConcurrentModificationException", 0, exceptionCount.get())
    }

    @Test
    fun testGuideKeyCandidatesCaching() {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)

        // Get private field guideKeyCandidatesCache using reflection
        val cacheField = IptvRepository::class.java.getDeclaredField("guideKeyCandidatesCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(repository) as Map<*, *>

        val method = IptvRepository::class.java.getDeclaredMethod("guideKeyCandidates", String::class.java)
        method.isAccessible = true

        val initialSize = cache.size

        // First call
        val result1 = method.invoke(repository, "NPO 1 FHD [NL]") as Set<String>
        val sizeAfterFirst = cache.size
        assertEquals(initialSize + 1, sizeAfterFirst)

        // Second call with same value (should hit cache)
        val result2 = method.invoke(repository, "NPO 1 FHD [NL]") as Set<String>
        assertEquals(sizeAfterFirst, cache.size)
        assertEquals(result1, result2)
    }

    @Test
    fun testFetchAndParseEpgThrows304Exception() {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>()
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)

        val builder = io.mockk.mockk<okhttp3.OkHttpClient.Builder>(relaxed = true)
        io.mockk.every { okHttpClient.newBuilder() } returns builder
        io.mockk.every { builder.connectTimeout(any(), any()) } returns builder
        io.mockk.every { builder.readTimeout(any(), any()) } returns builder
        io.mockk.every { builder.writeTimeout(any(), any()) } returns builder
        io.mockk.every { builder.callTimeout(any(), any()) } returns builder

        val customClient = io.mockk.mockk<okhttp3.OkHttpClient>()
        io.mockk.every { builder.build() } returns customClient

        val call = io.mockk.mockk<okhttp3.Call>()
        val response = io.mockk.mockk<okhttp3.Response>()
        io.mockk.every { response.code } returns 304
        io.mockk.every { response.close() } returns Unit
        io.mockk.every { call.execute() } returns response
        io.mockk.every { customClient.newCall(any()) } returns call

        val repository = IptvRepository(context, okHttpClient, profileManager, invalidationBus)
        val method = IptvRepository::class.java.getDeclaredMethod("fetchAndParseEpg", String::class.java, List::class.java)
        method.isAccessible = true

        try {
            method.invoke(repository, "http://example.com/epg.xml", emptyList<com.arflix.tv.data.model.IptvChannel>())
            fail("Expected EpgNotModifiedException to be thrown")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            assertEquals(IptvRepository.EpgNotModifiedException::class.java, cause?.javaClass)
        }
    }

    @Test
    fun testXtreamAliasResolutionPrefersExactKeyInsteadOfUnioningWeakAliases() {
        val repository = newRepository()
        val method = IptvRepository::class.java.getDeclaredMethod(
            "resolveChannelIdsFromLookup",
            Map::class.java,
            String::class.java
        )
        method.isAccessible = true

        val lookup = linkedMapOf(
            "news hd" to listOf("channel-news-hd"),
            "newshd" to listOf("channel-news-hd"),
            "news" to listOf("unrelated-news-channel")
        )

        @Suppress("UNCHECKED_CAST")
        val resolved = method.invoke(repository, lookup, "News HD") as List<String>

        assertEquals(listOf("channel-news-hd"), resolved)
    }

    @Test
    fun testXmltvExactIdentityWinsOverQualityAlias() {
        val repository = newRepository()
        val hdChannel = IptvChannel(
            id = "channel-news-hd",
            name = "News HD",
            streamUrl = "https://example.test/live/1",
            group = "News",
            epgId = "news hd"
        )
        val baseChannel = IptvChannel(
            id = "channel-news",
            name = "News",
            streamUrl = "https://example.test/live/2",
            group = "News",
            epgId = "news"
        )

        val buildLookup = IptvRepository::class.java.getDeclaredMethod(
            "buildChannelKeyLookup",
            List::class.java
        ).apply { isAccessible = true }
        val lookup = buildLookup.invoke(repository, listOf(hdChannel, baseChannel))
        val resolve = IptvRepository::class.java.getDeclaredMethod(
            "resolveXmlTvChannels",
            String::class.java,
            Map::class.java,
            lookup.javaClass
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val resolved = resolve.invoke(
            repository,
            "news",
            emptyMap<String, Set<String>>(),
            lookup
        ) as List<IptvChannel>

        assertEquals(listOf("channel-news"), resolved.map { it.id })
    }

    @Test
    fun testXmltvProviderIdentifierAliasesMatchReversedRegionAndQuality() {
        val repository = newRepository()
        val channel = IptvChannel(
            id = "channel-cnn-us",
            name = "CNN",
            streamUrl = "https://example.test/live/cnn",
            group = "News",
            epgId = "cnn.us"
        )
        val buildLookup = IptvRepository::class.java.getDeclaredMethod(
            "buildChannelKeyLookup",
            List::class.java
        ).apply { isAccessible = true }
        val lookup = buildLookup.invoke(repository, listOf(channel))
        val resolve = IptvRepository::class.java.getDeclaredMethod(
            "resolveXmlTvChannels",
            String::class.java,
            Map::class.java,
            lookup.javaClass
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val resolved = resolve.invoke(
            repository,
            "US.CNN.HD",
            emptyMap<String, Set<String>>(),
            lookup
        ) as List<IptvChannel>

        assertEquals(listOf("channel-cnn-us"), resolved.map { it.id })
    }

    @Test
    fun testXmltvProviderIdentifierAliasesDoNotMergeRegionalFeeds() {
        val repository = newRepository()
        val east = IptvChannel(
            id = "channel-cnn-east",
            name = "CNN East",
            streamUrl = "https://example.test/live/cnn-east",
            group = "News",
            epgId = "cnn.east.us"
        )
        val west = IptvChannel(
            id = "channel-cnn-west",
            name = "CNN West",
            streamUrl = "https://example.test/live/cnn-west",
            group = "News",
            epgId = "cnn.west.us"
        )
        val buildLookup = IptvRepository::class.java.getDeclaredMethod(
            "buildChannelKeyLookup",
            List::class.java
        ).apply { isAccessible = true }
        val lookup = buildLookup.invoke(repository, listOf(east, west))
        val resolve = IptvRepository::class.java.getDeclaredMethod(
            "resolveXmlTvChannels",
            String::class.java,
            Map::class.java,
            lookup.javaClass
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val resolved = resolve.invoke(
            repository,
            "US.CNN.HD",
            emptyMap<String, Set<String>>(),
            lookup
        ) as List<IptvChannel>

        assertEquals(emptyList<String>(), resolved.map { it.id })
    }

    @Test
    fun testEmbeddedEpgUrlIsScopedToItsPlaylist() {
        val repository = newRepository()
        val remember = IptvRepository::class.java.getDeclaredMethod(
            "rememberDiscoveredEpgUrl",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val resolve = IptvRepository::class.java.getDeclaredMethod(
            "resolveScopedEpgCandidates",
            IptvConfig::class.java,
        ).apply { isAccessible = true }
        val candidateClass = Class.forName(
            "com.arflix.tv.data.repository.IptvRepository\$ScopedEpgCandidate"
        )
        val playlistIdField = candidateClass.getDeclaredField("playlistId").apply { isAccessible = true }

        remember.invoke(repository, "https://provider-one.test/guide.xml", "one")
        @Suppress("UNCHECKED_CAST")
        val candidates = resolve.invoke(
            repository,
            IptvConfig(
                playlists = listOf(
                    IptvPlaylistEntry("one", "One", "https://provider-one.test/list.m3u"),
                    IptvPlaylistEntry("two", "Two", "https://provider-two.test/list.m3u"),
                )
            )
        ) as List<Any>

        assertEquals(listOf("one"), candidates.map { playlistIdField.get(it) })
    }

    @Test
    fun testUnscopedCachedEmbeddedEpgUrlIsNotAppliedAcrossPlaylists() {
        val repository = newRepository()
        val rememberLegacy = IptvRepository::class.java.getDeclaredMethod(
            "rememberDiscoveredEpgUrls",
            List::class.java,
        ).apply { isAccessible = true }
        val resolve = IptvRepository::class.java.getDeclaredMethod(
            "resolveScopedEpgCandidates",
            IptvConfig::class.java,
        ).apply { isAccessible = true }

        rememberLegacy.invoke(repository, listOf("https://unknown-source.test/guide.xml"))
        @Suppress("UNCHECKED_CAST")
        val candidates = resolve.invoke(
            repository,
            IptvConfig(
                playlists = listOf(
                    IptvPlaylistEntry("one", "One", "https://provider-one.test/list.m3u"),
                    IptvPlaylistEntry("two", "Two", "https://provider-two.test/list.m3u"),
                )
            )
        ) as List<Any>

        assertEquals(emptyList<Any>(), candidates)
    }

    @Test
    fun testXtreamNowNextUsesDifferentUpcomingProgramsForNextAndLater() {
        val repository = newRepository()
        val listingClass = Class.forName(
            "com.arflix.tv.data.repository.IptvRepository\$XtreamEpgListing"
        )
        val listingConstructor = listingClass.declaredConstructors
            .single { it.parameterTypes.size == 12 }
            .apply { isAccessible = true }
        val credentialsClass = Class.forName(
            "com.arflix.tv.data.repository.IptvRepository\$XtreamCredentials"
        )
        val credentials = credentialsClass.declaredConstructors
            .single { it.parameterTypes.size == 3 }
            .apply { isAccessible = true }
            .newInstance("https://example.test", "user", "password")

        val nowSeconds = System.currentTimeMillis() / 1_000L
        fun listing(title: String, startOffsetSeconds: Long): Any {
            val start = nowSeconds + startOffsetSeconds
            val stop = start + 1_500L
            val encodedTitle = Base64.getEncoder().encodeToString(title.toByteArray())
            return listingConstructor.newInstance(
                null,
                null,
                encodedTitle,
                null,
                null,
                null,
                null,
                null,
                start.toString(),
                stop.toString(),
                "7",
                null
            )
        }

        val buildMethod = IptvRepository::class.java.getDeclaredMethod(
            "buildNowNextFromXtreamListings",
            credentialsClass,
            List::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        buildMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val result = buildMethod.invoke(
            repository,
            credentials,
            listOf(
                listing("First programme", 600L),
                listing("First programme", 600L),
                listing("Second programme", 2_400L),
                listing("Third programme", 4_200L)
            ),
            emptyMap<String, List<String>>(),
            mapOf("7" to listOf("channel-7")),
            mapOf(
                "channel-7" to IptvChannel(
                    id = "channel-7",
                    name = "Channel 7",
                    streamUrl = "https://example.test/live/7",
                    group = "News",
                    xtreamStreamId = 7
                )
            ),
            false
        ) as Map<String, IptvNowNext>

        val item = result.getValue("channel-7")
        assertEquals("First programme", item.next?.title)
        assertEquals("Second programme", item.later?.title)
        assertNotEquals(item.next?.startUtcMillis, item.later?.startUtcMillis)
        assertEquals(3, item.upcoming.size)
    }

    private fun newRepository(): IptvRepository {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val okHttpClient = io.mockk.mockk<okhttp3.OkHttpClient>(relaxed = true)
        val profileManager = io.mockk.mockk<com.arflix.tv.data.repository.ProfileManager>(relaxed = true)
        val invalidationBus = io.mockk.mockk<com.arflix.tv.data.repository.CloudSyncInvalidationBus>(relaxed = true)
        return IptvRepository(context, okHttpClient, profileManager, invalidationBus)
    }
}
