@file:OptIn(androidx.tv.foundation.ExperimentalTvFoundationApi::class)

package com.arflix.tv.ui.screens.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.TvGridCells
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class)
class TvWidgetCompatibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingTvRowRendersOnAlignedFoundation() {
        compose.setContent {
            TvLazyRow(Modifier.size(320.dp, 100.dp)) { item { Text("TV row") } }
        }
        compose.waitForIdle()
    }

    @Test fun existingTvGridRendersOnAlignedFoundation() {
        compose.setContent {
            TvLazyVerticalGrid(TvGridCells.Fixed(2), Modifier.size(320.dp, 200.dp)) {
                item { Text("TV grid") }
            }
        }
        compose.waitForIdle()
    }
}
