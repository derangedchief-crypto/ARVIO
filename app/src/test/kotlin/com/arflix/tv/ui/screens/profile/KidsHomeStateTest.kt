package com.arflix.tv.ui.screens.profile

import com.arflix.tv.data.api.TmdbMediaItem
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.KidsCatalogRepository
import com.arflix.tv.data.repository.KidsTitle
import com.arflix.tv.data.repository.WatchlistRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class KidsHomeStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = mockk<KidsCatalogRepository>()
    private val watchlist = mockk<WatchlistRepository>()
    private fun title(id: Int, type: MediaType) = KidsTitle(TmdbMediaItem(
        id = id, title = "Verified $id", posterPath = "/poster.jpg", backdropPath = "/backdrop.jpg"
    ), type)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { catalog.browse(any(), any(), any()) } returns emptyList()
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun verifiedMoviesAndShowsUseOrdinaryHomeRowsAndArtwork() = runTest(dispatcher) {
        coEvery { catalog.browse(MediaType.MOVIE, 1, "") } returns listOf(title(1, MediaType.MOVIE))
        coEvery { catalog.browse(MediaType.TV, 1, "") } returns listOf(title(2, MediaType.TV))
        val vm = KidsViewModel(catalog, watchlist)
        advanceUntilIdle()
        val state = vm.home.value
        assertEquals(listOf("Movies", "TV shows"), state.categories.map { it.title })
        assertEquals(listOf(MediaType.MOVIE, MediaType.TV), state.categories.map { it.items.single().mediaType })
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", state.heroItem?.backdrop)
        assertFalse(state.isLoading)
        assertFalse(state.trailerAutoPlay)
    }

    @Test fun searchClearsPreviousShelvesAndAppendsOnlyMatchingVerifiedPages() = runTest(dispatcher) {
        coEvery { catalog.browse(MediaType.MOVIE, 1, "") } returns listOf(title(1, MediaType.MOVIE))
        coEvery { catalog.browse(MediaType.MOVIE, 1, "robot") } returns listOf(title(3, MediaType.MOVIE))
        coEvery { catalog.browse(MediaType.MOVIE, 2, "robot") } returns listOf(title(3, MediaType.MOVIE), title(4, MediaType.MOVIE))
        val vm = KidsViewModel(catalog, watchlist)
        advanceUntilIdle()
        vm.load(" robot ")
        assertTrue(vm.home.value.categories.isEmpty())
        advanceUntilIdle()
        vm.loadMore("kids_movie")
        advanceUntilIdle()
        assertEquals(listOf(3, 4), vm.home.value.categories.single().items.map { it.id })
    }

    @Test fun libraryReverifiesStoredItemsAndNeverDisplaysUnapprovedTitles() = runTest(dispatcher) {
        coEvery { watchlist.getLocalWatchlistItems() } returns listOf(MediaItem(1, "Allowed"), MediaItem(2, "Blocked"))
        coEvery { catalog.allowed(any(), 1) } returns true
        coEvery { catalog.allowed(any(), 2) } returns false
        val vm = KidsViewModel(catalog, watchlist)
        advanceUntilIdle()
        vm.load(showLibrary = true)
        advanceUntilIdle()
        assertEquals(listOf(1), vm.home.value.categories.single().items.map { it.id })
        assertTrue(vm.home.value.categoryHasMoreMap.isEmpty())
    }
}
