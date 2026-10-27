package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arflix.tv.access.PrimaryAccessRepository
import com.arflix.tv.access.PrimaryAccessState
import com.arflix.tv.access.PrimaryGatePage
import com.arflix.tv.access.gatePage
import com.arflix.tv.ui.screens.xtreamgate.XtreamGateScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val LocalPrimaryAccess=staticCompositionLocalOf<PrimaryAccessRepository?> { null }
val LocalPrimaryState=staticCompositionLocalOf { PrimaryAccessState() }

/** Account restrictions are NOT activation. Keep the bound identity and wait for
 * automatic verification; do not invite customers to re-enter a code or trial. */
@Composable
fun PrimaryActivationGate(controller:PrimaryAccessRepository,content: @Composable () -> Unit){
    val state by controller.state.collectAsState()
    CompositionLocalProvider(LocalPrimaryAccess provides controller,LocalPrimaryState provides state){
        when(state.gatePage()){
            PrimaryGatePage.CHECKING -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            PrimaryGatePage.ACTIVATION -> {
                BackHandler{}
                Column(Modifier.fillMaxSize().padding(16.dp)){
                    Text(state.message)
                    Box(Modifier.weight(1f)){XtreamGateScreen(allowTrial=!state.bound,onLoginSuccess={controller.finishActivation();controller.retry()},onSkip={})}
                }
            }
            PrimaryGatePage.ACCOUNT_INACTIVE -> AccountAccessNotice(inactive=true)
            PrimaryGatePage.ACCOUNT_RECOVERY -> AccountRecoveryNotice()
            PrimaryGatePage.VERIFICATION_UNAVAILABLE -> AccountAccessNotice(inactive=false)
            PrimaryGatePage.CONTENT -> key(state.restrictionEpoch){content()}
        }
    }
}

@Composable
private fun AccountRecoveryNotice(){
    BackHandler{}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=680.dp).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)){
            Text("Unable to restore account access",style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center)
            Text("Your saved account could not be safely restored. We’ll check again automatically. If this continues, please contact your provider.",style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center)
            Text("Do not uninstall the app or clear its data.",style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center)
        }
    }
}

@Composable
private fun AccountAccessNotice(inactive:Boolean){
    BackHandler{}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=680.dp).verticalScroll(rememberScrollState()).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)){
            Text(if(inactive)"Your account is expired or inactive" else "Unable to verify your account",style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center)
            Text(if(inactive)"Please contact your provider to renew or reactivate your account." else "We can’t verify your account right now. Please check your internet connection.",style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center)
            Text(if(inactive)"Access will return automatically once your account is active." else "We’ll check again automatically.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
            Text("Your saved playlists, favourites and settings are safe.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
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
