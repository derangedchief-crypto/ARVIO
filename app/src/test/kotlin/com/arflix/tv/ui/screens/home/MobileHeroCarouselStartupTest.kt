package com.arflix.tv.ui.screens.home

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Render the real nonempty phone carousel, not an empty/loading home placeholder. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class)
class MobileHeroCarouselStartupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun populatedHomePagerRendersWithoutMissingMethod() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MobileHeroCarousel(
                categories = listOf(Category("regression", "Regression",
                    listOf(MediaItem(1, "Pager regression"), MediaItem(2, "Second item")))),
                onNavigateToDetails = { _, _, _, _ -> },
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
