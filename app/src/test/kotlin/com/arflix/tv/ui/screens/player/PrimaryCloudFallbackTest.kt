package com.arflix.tv.ui.screens.player

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.arflix.tv.access.PrimaryPlaybackDeniedException
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.data.repository.StreamRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class PrimaryCloudFallbackTest {
    private val cloud=StreamSource("Cloud","Extreme Cloud","cloud-fixture","1080p","",url="https://cdn.example.test/movie.mp4")
    @Test fun invalidPrimaryCannotSelectEvenAnAlreadyKnownDirectCloudUrl()=fixture(initiallyValid=false)
    @Test fun resolverPolicyDenialCannotBecomeRawUrlFallback()=fixture(resolverDenies=true)
    @Test fun invalidationDuringCloudResolveCannotPublishTheCompletedUrl()=fixture(invalidateDuringResolve=true)
    @Test fun deniedHubCloudResolveCannotTryAnotherCloudCandidate()=fixture(resolverDenies=true,hub=true)

    private fun fixture(initiallyValid:Boolean=true,resolverDenies:Boolean=false,invalidateDuringResolve:Boolean=false,hub:Boolean=false)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var valid=initiallyValid
        val streams=mockk<StreamRepository>(relaxed=true)
        every{streams.extremeCloudAccess}returns MutableStateFlow(true)
        fun check():Long {if(!valid)throw PrimaryPlaybackDeniedException();return 1L}
        every{streams.requirePrimaryPlayback(null)}answers{check()}
        every{streams.requirePrimaryPlayback(1L)}answers{check()}
        coEvery{streams.resolveStreamForPlayback(any())}coAnswers{
            if(resolverDenies)throw PrimaryPlaybackDeniedException()
            if(invalidateDuringResolve)valid=false
            firstArg()
        }
        val vm=PlayerViewModel(RuntimeEnvironment.getApplication(),mockk(relaxed=true),mockk(relaxed=true),streams,
            mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        try {
            runCurrent()
            val selected=if(hub)cloud.copy(url="https://hubcloud.cx/drive/fixture")else cloud
            val stateField=PlayerViewModel::class.java.getDeclaredField("_uiState").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST") val state=stateField.get(vm) as MutableStateFlow<PlayerUiState>
            state.value=state.value.copy(streams=listOf(selected,cloud.copy(url="https://other.example.test/backup.mp4")))
            vm.selectStream(selected);runCurrent()
            assertNull(vm.uiState.value.selectedStreamUrl);assertNull(vm.uiState.value.selectedStream)
            assertFalse(vm.uiState.value.isLoading);assertTrue(vm.uiState.value.error.orEmpty().contains("Primary"))
            coVerify(exactly=if(initiallyValid)1 else 0){streams.resolveStreamForPlayback(any())}
        } finally {vm.viewModelScope.cancel();runCurrent();Dispatchers.resetMain()}
    }
}
