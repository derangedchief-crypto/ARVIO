package com.arflix.tv.ui.screens.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow as TvLazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid as TvLazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells as TvGridCells
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
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

    @Test fun tvRowScrollExercisesRuntimePrefetchInsteadOfOnlyInitialComposition() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            state = androidx.compose.foundation.lazy.rememberLazyListState()
            TvLazyRow(Modifier.size(320.dp, 100.dp).testTag("tv-row"), state = state) {
                items(100) { Text("Item $it", Modifier.size(100.dp, 80.dp)) }
            }
        }
        compose.onNodeWithTag("tv-row").performTouchInput { swipeLeft(durationMillis = 700) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
    }

    @Test fun tvGridScrollExercisesRuntimePrefetchInsteadOfOnlyInitialComposition() {
        lateinit var state: androidx.compose.foundation.lazy.grid.LazyGridState
        compose.setContent {
            state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            TvLazyVerticalGrid(TvGridCells.Fixed(2), Modifier.size(320.dp, 240.dp).testTag("tv-grid"), state = state) {
                items(100) { Text("Item $it", Modifier.size(120.dp, 80.dp)) }
            }
        }
        compose.onNodeWithTag("tv-grid").performTouchInput { swipeUp(durationMillis = 700) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
    }

    @Test fun retiredBinaryTvLazyWidgetsAreNotOnTheClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("androidx.tv.foundation.lazy.list.TvLazyListState", false, javaClass.classLoader)
        }
    }
}
