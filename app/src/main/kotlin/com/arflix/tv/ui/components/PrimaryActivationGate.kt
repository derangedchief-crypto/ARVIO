package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arflix.tv.access.PrimaryAccessRepository
import com.arflix.tv.access.PrimaryAccessState
import com.arflix.tv.ui.screens.xtreamgate.XtreamGateScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val LocalPrimaryAccess=staticCompositionLocalOf<PrimaryAccessRepository?> { null }
val LocalPrimaryState=staticCompositionLocalOf { PrimaryAccessState() }

/** The recovery route is outside ordinary navigation. Returning/back/deep links
 * cannot reveal old players; optional first-run setup resumes after confirmation. */
@Composable
fun PrimaryActivationGate(controller:PrimaryAccessRepository,content: @Composable () -> Unit){
    val state by controller.state.collectAsState()
    CompositionLocalProvider(LocalPrimaryAccess provides controller,LocalPrimaryState provides state){
        when{
            state.checking -> Box(Modifier.fillMaxSize()){CircularProgressIndicator()}
            !state.primaryAllowed -> {
                BackHandler{}
                Column(Modifier.fillMaxSize().padding(16.dp)){
                    Text(state.message)
                    Box(Modifier.weight(1f)){XtreamGateScreen(allowTrial=!state.bound,onLoginSuccess={controller.finishActivation();controller.retry()},onSkip={})}
                }
            }
            else -> key(state.restrictionEpoch){content()}
        }
    }
}

/** A non-lifecycle-paused collector also stops controlled playback in background
 * and PiP when a verified grant expires. The callback must release BOTH engines. */
@Composable
fun ObservePrimaryPlayback(allowed:()->Boolean={true},onRestricted:()->Unit){
    val controller=LocalPrimaryAccess.current
    val stop by rememberUpdatedState(onRestricted)
    val permitted by rememberUpdatedState(allowed)
    DisposableEffect(controller){val close=controller?.watchPlayback();onDispose{close?.invoke()}}
    LaunchedEffect(controller){
        var binding=controller?.state?.value?.bindingRevision
        while(isActive&&controller!=null){
            val state=controller.state.value
            if(!controller.primaryAllowed()||!permitted()||binding!=state.bindingRevision)stop()
            binding=state.bindingRevision
            delay(250)
        }
    }
}
