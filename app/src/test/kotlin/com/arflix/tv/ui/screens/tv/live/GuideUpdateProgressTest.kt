package com.arflix.tv.ui.screens.tv.live

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class GuideUpdateProgressTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tvUpdateBarIsFullWidthWhileSavedGuideRemainsVisible() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(Modifier.requiredSize(736.dp, 300.dp)) {
                GuideBackfillStatus(true, 46, "Downloading server guide", compact = true)
                Text("Saved programme stays visible")
            }
        }
        compose.onNodeWithText("Saved programme stays visible").assertExists()
        val bar = compose.onNodeWithTag("guide-update-progress")
        assertTrue(bar.fetchSemanticsNode().boundsInRoot.width >= 700)
        assertEquals(0.46f, bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
    }
    @Test fun failureReportsRetainedListingsWithoutAnEndlessProgressWheel() {
        compose.setContent {
            Column {
                GuideBackfillStatus(false, null, "Guide update unavailable — saved listings retained", compact = true, failed = true)
                Text("Saved programme stays visible")
            }
        }
        compose.onNodeWithTag("guide-update-progress").assertDoesNotExist()
        compose.onNodeWithText("saved listings retained", substring = true).assertExists()
        compose.onNodeWithText("Saved programme stays visible").assertExists()
    }
}
