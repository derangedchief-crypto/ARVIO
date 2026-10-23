package com.arflix.tv.trials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrialSignupUiState(
    val available: Boolean=false,val formOpen: Boolean=false,val brandName: String="",val durationLabel: String="",val notice: String="",
    val firstName: String="",val phone: String="",val busy: Boolean=false,val pending: Boolean=false,val phase: String?=null,
    val error: String?=null,val activationCode: String?=null,val handoffAttempted: Boolean=false
)

@HiltViewModel
class TrialSignupViewModel @Inject constructor(private val repository: TrialSignupRepository) : ViewModel() {
    private val mutable=MutableStateFlow(TrialSignupUiState());val uiState=mutable.asStateFlow();private var work: Job?=null
    init {
        if(BuildConfig.BRAND_ID.isNotBlank())viewModelScope.launch {
            val offer=try { repository.offer() }catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){null}
            val pending=repository.pending()
            if(offer?.available==true)mutable.value=mutable.value.copy(available=true,brandName=offer.brandName,durationLabel="${offer.duration} ${offer.period}",notice=offer.notice,pending=pending)
            else if(pending)mutable.value=mutable.value.copy(available=true,pending=true,phase="An earlier trial request is tracked. Retry it or activate normally.")
        }
    }
    fun open(){
        val opening=!mutable.value.formOpen;mutable.value=mutable.value.copy(formOpen=opening,error=null)
        if(opening&&!mutable.value.pending)viewModelScope.launch {
            mutable.value=mutable.value.copy(busy=true)
            try{val o=repository.offer();mutable.value=mutable.value.copy(available=o.available,brandName=o.brandName,durationLabel="${o.duration} ${o.period}",notice=o.notice,busy=false)}
            catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_:Exception){mutable.value=mutable.value.copy(busy=false,error="Trial offer unavailable. Use normal activation or contact your seller.")}
        }
    }
    fun firstName(value: String){if(!mutable.value.busy)mutable.value=mutable.value.copy(firstName=value.take(60),error=null)}
    fun phone(value: String){if(!mutable.value.busy)mutable.value=mutable.value.copy(phone=value.take(50),error=null)}
    fun start(){if(mutable.value.busy)return;val current=mutable.value;runCatching { TrialContact.parse(current.firstName,current.phone) }.onFailure { mutable.value=current.copy(error=it.message);return };begin { repository.start(current.firstName,current.phone) }}
    fun retry(){if(!mutable.value.busy)begin { repository.retry() }}
    private fun begin(action: suspend ()->TrialProgress){
        work?.cancel();mutable.value=mutable.value.copy(busy=true,error=null,phase="Requesting your trial. Queued is not yet activated.")
        work=viewModelScope.launch {
            try {
                var progress=action();val deadline=System.currentTimeMillis()+180_000
                while(progress.state in setOf("reserved","sending","retry_wait","registration_pending")&&System.currentTimeMillis()<deadline){
                    mutable.value=mutable.value.copy(pending=true,phase=if(progress.state=="registration_pending")"Trial created; waiting for panel registration. Do not start another." else "Trial setup is still waiting. The same request is being tracked.")
                    delay(3000);progress=repository.retry()
                }
                mutable.value=mutable.value.copy(busy=false,pending=progress.state !in setOf("failed","cancelled","delivered"),activationCode=progress.activationCode,handoffAttempted=false,
                    phase=when(progress.state){"prepared"->"Trial created. Activating through the normal sign-in flow…";"delivered","already_created"->"A trial was already created. Continue normal activation or contact your seller.";"failed","cancelled"->"This request did not complete. Contact your seller before starting another.";"needs_review"->"Trial outcome needs seller review. A second account will not be created automatically.";else->"Still waiting. Use Retry existing request, not a new trial."})
            }catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error: Exception){val tracked=repository.pending();mutable.value=mutable.value.copy(busy=false,pending=tracked,error=error.message?:"Trial setup needs review. Retry the existing request.")}
        }
    }
    fun handoffStarted(){mutable.value=mutable.value.copy(handoffAttempted=true)}
    fun activationCompleted(){repository.activationCompleted();mutable.value=TrialSignupUiState()}
}
