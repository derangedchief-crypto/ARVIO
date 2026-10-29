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
import kotlinx.coroutines.launch
import com.arflix.tv.BuildConfig
import com.arflix.tv.management.RenewalCheckoutState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

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
            PrimaryGatePage.ACCOUNT_INACTIVE -> if(BuildConfig.BRAND_ID.isBlank())AccountAccessNotice(inactive=true) else BrandedExpiredAccount(controller)
            PrimaryGatePage.ACCOUNT_RECOVERY -> AccountRecoveryNotice()
            PrimaryGatePage.VERIFICATION_UNAVAILABLE -> AccountAccessNotice(inactive=false)
            PrimaryGatePage.CONTENT -> key(state.restrictionEpoch){content()}
        }
    }
}

@Composable
private fun BrandedExpiredAccount(controller:PrimaryAccessRepository){
    val repository=remember(controller){controller.renewalCheckout}
    val checkout by repository.state.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    LaunchedEffect(repository){repository.load()}
    DisposableEffect(repository){onDispose{repository.clear()}}
    LaunchedEffect(repository,showQr,checkout.url){
        var checks=0
        if(showQr&&checkout.url!=null)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive&&checks++<180){repository.poll();delay(10_000)}}
    }
    RenewalAccountNotice(checkout,showQr,{showQr=true},{scope.launch{repository.load()}})
}

@Composable
internal fun RenewalAccountNotice(checkout:RenewalCheckoutState,showQr:Boolean,onShowQr:()->Unit,onRefresh:()->Unit){
    BackHandler{}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=700.dp).verticalScroll(rememberScrollState()).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(if(checkout.url!=null)"Your subscription has expired" else "Your account is expired or inactive",style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center)
            Text("Please contact your provider to renew or reactivate your account.",textAlign=TextAlign.Center)
            if(checkout.url!=null){
                if(checkout.simulated)Text("DEMO ONLY — no money or real renewal",color=MaterialTheme.colorScheme.error,textAlign=TextAlign.Center)
                if(showQr&&checkout.qrExpired){Text("This QR has expired. Refresh the link to continue the same renewal.",textAlign=TextAlign.Center);Button(onClick=onRefresh){Text("Refresh QR")}}
                else if(showQr){QrCodeImage(checkout.url,256,Modifier.size(220.dp));Text("Scan with your phone to view your provider’s renewal options.",textAlign=TextAlign.Center)}
                else Button(onClick=onShowQr){Text("Renew subscription")}
            }
            checkout.message?.let{Text(it,textAlign=TextAlign.Center)}
            Text("Access returns only after your account is verified active. Do not uninstall or clear app data.",style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
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
