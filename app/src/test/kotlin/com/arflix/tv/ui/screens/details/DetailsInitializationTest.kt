package com.arflix.tv.ui.screens.details

import androidx.lifecycle.ViewModelStore
import com.arflix.tv.core.plugin.PluginManager
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.StreamRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailsInitializationTest {
    @Test fun streamingGraphIsDeferredOffNavigationThreadAndScrapersStayLazy()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store=ViewModelStore()
        try {
            val navigationThread=Thread.currentThread()
            var constructedOn:Thread?=null
            val stream=mockk<StreamRepository>()
            every { stream.extremeCloudAccess } returns flowOf(true)
            val streams=mockk<dagger.Lazy<StreamRepository>>()
            every { streams.get() } answers { constructedOn=Thread.currentThread();stream }
            val plugins=mockk<dagger.Lazy<PluginManager>>()
            val media=mockk<MediaRepository>()
            every { media.episodeRatingsUpdated } returns MutableSharedFlow()
            val vm=DetailsViewModel(context=mockk(relaxed=true),mediaRepository=media,
                mdbListRepository=mockk(),pluginManagerProvider=plugins,profileManager=mockk(),
                traktRepository=mockk(),remoteSyncManager=mockk(),streamRepositoryProvider=streams,
                animeMapper=mockk(),tmdbApi=mockk(),watchHistoryRepository=mockk(),
                watchlistRepository=mockk(),cloudSyncRepository=mockk(),launcherContinueWatchingRepository=mockk())
            store.put("details",vm)
            verify(exactly=0){streams.get()};verify(exactly=0){plugins.get()}
            runCurrent()
            val allowed=withContext(Dispatchers.Default){withTimeout(5000){vm.canSearchExtremeCloud.first{it}}}
            assertTrue(allowed);assertNotSame(navigationThread,constructedOn)
            verify(exactly=1){streams.get()};verify(exactly=0){plugins.get()}
        }finally{store.clear();runCurrent();Dispatchers.resetMain()}
    }
}
