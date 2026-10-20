package com.arflix.tv.data.repository

import android.app.Application
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GuideOwnershipRetentionTest {
    @Test fun missingUpgradeMarkerAndEpgUrlChangeDoNotEraseExistingGuide() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("arvio_iptv_epg_index.db")
        context.getSharedPreferences("arvio_iptv_prefs", 0).edit().clear().commit()
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "fixture"
        val repository = IptvRepository(context, OkHttpClient(), profile, mockk(relaxed = true), IptvEpgPlaybackGate())
        val config = IptvConfig(m3uUrl = "https://provider.example.test/list.m3u", epgUrl = "https://guide.example.test/old.xml")
        val type = IptvRepository::class.java
        val keyMethod = type.getDeclaredMethod("epgIndexKey", String::class.java, IptvConfig::class.java).apply { isAccessible = true }
        val key = keyMethod.invoke(repository, "fixture", config) as String
        @Suppress("UNCHECKED_CAST")
        val index = (type.getDeclaredField("epgIndex\$delegate").apply { isAccessible = true }.get(repository) as Lazy<IptvEpgIndex>).value
        val ensure = type.getDeclaredMethod("ensureCacheOwnership", String::class.java, IptvConfig::class.java).apply { isAccessible = true }
        val now = System.currentTimeMillis()
        try {
            index.mergeChannels(key, mapOf("channel" to IptvNowNext(now = IptvProgram("Saved programme", startUtcMillis = now - 1, endUtcMillis = now + 3_600_000))), now)
            val generation = index.sourceState(key)!!.generation
            ensure.invoke(repository, "fixture", config)
            assertEquals("Saved programme", index.loadWindow(key, setOf("channel"), now, now + 3_600_000).getValue("channel").single().title)
            ensure.invoke(repository, "fixture", config.copy(epgUrl = "https://guide.example.test/new.xml"))
            assertEquals(generation, index.sourceState(key)!!.generation)
            assertEquals("Saved programme", index.loadWindow(key, setOf("channel"), now, now + 3_600_000).getValue("channel").single().title)
            assertEquals("stale", index.sourceState(key)!!.status)
            kotlinx.coroutines.runBlocking { repository.expireIptvResolverCachesKeepingGuide() }
            assertEquals("Saved programme", index.loadWindow(key, setOf("channel"), now, now + 3_600_000).getValue("channel").single().title)
        } finally {
            index.close()
            (type.getDeclaredField("backgroundEpgDispatcher").apply { isAccessible = true }.get(repository) as kotlinx.coroutines.ExecutorCoroutineDispatcher).close()
        }
    }
}
