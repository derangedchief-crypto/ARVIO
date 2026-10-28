package com.arflix.tv.management

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Best-effort matching hint, NOT hardware authentication. No new permissions,
 * raw identifiers in requests/logs, app-private seed, or cross-brand matching. */
internal object DeviceRecoveryHint {
    fun read(context:Context):String? = if(Build.VERSION.SDK_INT<26)null else runCatching{
        derive(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID),context.packageName)
    }.getOrNull()
    fun derive(androidId:String?,appPackage:String):String? {
        val id=androidId?.lowercase()?.trim()?:return null
        if(!id.matches(Regex("[a-f0-9]{16}"))||id in setOf("0000000000000000","ffffffffffffffff","9774d56d682e549c"))return null
        return managementHash("extreme-device-recovery-v1\n$appPackage\n$id")
    }
    fun signingText(deviceId:String,appPackage:String,hint:String)="ECRECOVERYHINT1\n$deviceId\n$appPackage\n$hint"
}

/** A website rollback may remove a capability advertised in a prior heartbeat.
 * Retry once without optional fields only on schema rejection, NEVER on auth or
 * ownership conflicts. Normal check-in must keep working on the old protocol. */
internal suspend fun <T> optionalRecoveryCheckIn(hasHint:Boolean,send:suspend(Boolean)->T):T = try {
    send(hasHint)
} catch(error:ManagementHttpException){
    if(hasHint&&error.status==422)send(false) else throw error
}
