package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveChannelNameMarqueeTest {
    @get:Rule val compose = createComposeRule()
    private fun verify(active: Boolean, text: String, moves: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.width(180.dp).height(30.dp).background(Color.Black).testTag("channel-name")) {
                Text(text, color = Color.White, fontSize = 18.sp, maxLines = 1, softWrap = false,
                    overflow = if (active) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.liveChannelNameMarquee(active))
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("channel-name").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        val after = compose.onNodeWithTag("channel-name").captureToImage().asAndroidBitmap()
        assertEquals(!moves, before.sameAs(after))
    }
    @Test fun selectedLongChannelNameScrollsAfterPause() = verify(true, "CA | SPORTSNET ONTARIO VERY LONG CHANNEL NAME 4K", true)
    @Test fun inactiveLongRowsDoNotAllAnimate() = verify(false, "CA | SPORTSNET ONTARIO VERY LONG CHANNEL NAME 4K", false)
    @Test fun shortNamesStayStillEvenWhenSelected() = verify(true, "TSN", false)
}
