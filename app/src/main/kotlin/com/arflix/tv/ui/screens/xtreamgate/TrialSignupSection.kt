package com.arflix.tv.ui.screens.xtreamgate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.trials.TrialSignupUiState

/** Rendered only inside an actual branded build. No OTP or paid-plan picker. */
@Composable
internal fun TrialSignupSection(state: TrialSignupUiState,manualBusy: Boolean,onOpen: ()->Unit,onFirst: (String)->Unit,onPhone: (String)->Unit,onStart: ()->Unit,onRetry: ()->Unit,onActivate: ()->Unit) {
    if(!state.available)return
    var focused by remember { mutableStateOf("") };val keyboard=LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(top=20.dp)) {
        GateButton(onClick=onOpen,text=if(state.formOpen)"Hide trial setup" else "Get a trial${state.durationLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",isFocused=focused=="open",enabled=!manualBusy&&!state.busy,modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused)focused="open" })
        if(!state.formOpen&&!state.pending)return@Column
        if(state.formOpen&&!state.pending){
            Spacer(16.dp);Text("First name",color=Color.White,fontSize=13.sp)
            GateTextField(state.firstName,onFirst,"First name",onRequestKeyboard={keyboard?.show()},isFocused=focused=="first",modifier=Modifier.fillMaxWidth().semantics { contentDescription="Trial first name" }.onFocusChanged { if(it.isFocused)focused="first" })
            Spacer(12.dp);Text("Phone number with country code (+ optional)",color=Color.White,fontSize=13.sp)
            GateTextField(state.phone,onPhone,"1 555 123 4567",keyboardType=KeyboardType.Phone,onRequestKeyboard={keyboard?.show()},isFocused=focused=="phone",modifier=Modifier.fillMaxWidth().semantics { contentDescription="Trial phone number" }.onFocusChanged { if(it.isFocused)focused="phone" })
            Spacer(12.dp);Text(state.notice,color=Color.White.copy(alpha=.7f),fontSize=12.sp)
            Spacer(12.dp);GateButton(onClick={keyboard?.hide();onStart()},text=if(state.busy)"Setting up trial…" else "Start trial",isFocused=focused=="start",enabled=!manualBusy&&!state.busy,modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused)focused="start" })
        }
        state.phase?.let { Spacer(12.dp);Text(it,color=Color.White.copy(alpha=.7f),fontSize=12.sp) }
        state.error?.let { Spacer(12.dp);Text(it,color=Color(0xFFEF7777),fontSize=12.sp) }
        if(state.activationCode!=null){Spacer(12.dp);GateButton(onClick=onActivate,text="Activate my trial",isFocused=focused=="activate",enabled=!manualBusy&&!state.busy,modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused)focused="activate" })}
        else if(state.pending){Spacer(12.dp);GateButton(onClick=onRetry,text=if(state.busy)"Tracking the same request…" else "Retry existing request",isFocused=focused=="retry",enabled=!manualBusy&&!state.busy,modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused)focused="retry" })}
        Spacer(12.dp);Text("Normal activation remains available. Closing this form cannot recall work already started; retry the same request instead of starting another.",color=Color.White.copy(alpha=.5f),fontSize=11.sp)
    }
}
