package com.arflix.tv.management

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class ManagementEventCode { LIVE_TUNE_REQUEST, LIVE_FAST_STARTED, LIVE_MPV_STARTED, LIVE_RECOVERY, LIVE_STOP,
    GUIDE_REFRESH_REQUESTED, GUIDE_REFRESH_COMPLETED, GUIDE_REFRESH_FAILED, GUIDE_REFRESH_CANCELLED, PLAYLIST_REFRESH_OK, COMMAND_ACCEPTED, RECEIPT_SUCCEEDED, RECEIPT_FAILED, CACHE_CLEAR_PARTIAL, REOPEN_REQUESTED }

/** Fixed-code support history, not logcat. No API accepts free-form detail. */
internal object ManagementEvents {
    private var prefs: SharedPreferences? = null
    private var key: String? = null
    private val codes=ManagementEventCode.entries.map { it.name }.toSet()
    @Synchronized fun bind(context: Context,scope: String) {
        try {
        val store=context.applicationContext.getSharedPreferences("managed_event_history_v1",Context.MODE_PRIVATE)
        val current="events:"+managementHash(scope);prefs=store;key=current
        val old=store.all.keys.filter { it.startsWith("events:")&&it!=current }.sortedByDescending { store.getLong("seen:$it",0) }.drop(3)
        store.edit().apply { old.forEach { remove(it);remove("seen:$it") };putLong("seen:$current",System.currentTimeMillis()) }.apply()
        } catch(_: Exception) {key=null}
    }
    @Synchronized fun unbind() { key=null }
    @Synchronized fun record(code: ManagementEventCode) {
        try {
        val store=prefs?:return;val target=key?:return
        val now=System.currentTimeMillis();val prior=runCatching { JSONArray(store.getString(target,"[]")?.takeIf { it.length<=32768 }?:"[]") }.getOrElse { JSONArray() };val next=JSONArray()
        for(index in maxOf(0,prior.length()-127) until prior.length()) {
            val event=prior.optJSONObject(index)?:continue
            if(event.optString("code") in codes&&now-event.optLong("at_ms") in 0..604800000L)next.put(JSONObject().put("code",event.getString("code")).put("at_ms",event.getLong("at_ms")))
        }
        next.put(JSONObject().put("code",code.name).put("at_ms",now));store.edit().putString(target,next.toString()).apply()
        } catch(_: Exception) { /* Diagnostics must never interrupt playback/guide work. */ }
    }
    @Synchronized fun export(scope: String): JSONArray {
        try {
        val store=prefs?:return JSONArray();val requested="events:"+managementHash(scope)
        if(requested!=key)return JSONArray()
        val raw=runCatching { JSONArray(store.getString(requested,"[]")?.takeIf { it.length<=32768 }?:"[]") }.getOrElse { JSONArray() };val result=JSONArray();val now=System.currentTimeMillis()
        for(index in maxOf(0,raw.length()-128) until raw.length()) {val e=raw.optJSONObject(index)?:continue;val code=e.optString("code");val at=e.optLong("at_ms");if(code in codes&&now-at in 0..604800000L)result.put(JSONObject().put("code",code).put("at_ms",at))}
        return result
        } catch(_: Exception) {return JSONArray()}
    }
}
