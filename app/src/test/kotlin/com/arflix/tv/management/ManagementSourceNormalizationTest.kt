package com.arflix.tv.management

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.arflix.tv.data.repository.*
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class ManagementSourceNormalizationTest {
    @Test fun existingNativeNormalizerMustAgreeWithTargetBeforeAnySourceWrite() {
        val repo=IptvRepository(ApplicationProvider.getApplicationContext(),OkHttpClient(),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        val url="https://provider.test/get.php?username=fixture&password=safe-password&type=m3u_plus&output=ts"
        val guide="https://provider.test/xmltv.php?username=fixture&password=safe-password"
        val primary=IptvPlaylistEntry("list_1","Main",url,guide,epgUrls=listOf(guide))
        val config=IptvConfig(m3uUrl=url,epgUrl=guide,playlists=listOf(primary))
        assertTrue(repo.isStableManagedSourceTarget(config))
        assertFalse(repo.isStableManagedSourceTarget(config.copy(playlists=listOf(primary.copy(epgUrl=url,epgUrls=listOf(url))),epgUrl=url)))
        val encoded=url.replace("safe-password","unsafe%26password")
        assertFalse(repo.isStableManagedSourceTarget(config.copy(m3uUrl=encoded,playlists=listOf(primary.copy(m3uUrl=encoded)))))
    }
}
