package com.arflix.tv.data.repository

import androidx.annotation.Keep
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson

internal data class ManagedPreferenceRule(val default: String, val choices: List<String>, val boolean: Boolean = false)
internal val managedPreferenceRules = linkedMapOf(
    "default_audio_language" to ManagedPreferenceRule("Auto (Original)", listOf("Auto (Original)","English","French","Spanish","German","Italian","Portuguese")),
    "default_subtitle" to ManagedPreferenceRule("Off", listOf("Off","English","French","Spanish","German","Italian","Portuguese")),
    "subtitle_size" to ManagedPreferenceRule("Medium",listOf("Small","Medium","Large")),
    "clock_format" to ManagedPreferenceRule("24h",listOf("12h","24h")),
    "auto_play_next" to ManagedPreferenceRule("true",listOf("false","true"),true),
    "show_loading_stats" to ManagedPreferenceRule("true",listOf("false","true"),true),
    "spoiler_blur" to ManagedPreferenceRule("false",listOf("false","true"),true),
    "smooth_scrolling" to ManagedPreferenceRule("false",listOf("false","true"),true),
    "trailer_auto_play" to ManagedPreferenceRule("false",listOf("false","true"),true),
    "volume_boost_db" to ManagedPreferenceRule("0",listOf("0","3","6"))
)
@Keep internal data class ManagedPreferenceState(val base: Map<String,String> = emptyMap(), val overrides: Map<String,String> = emptyMap(),
    val prior: Map<String,String?> = emptyMap(),val applied: Map<String,String> = emptyMap(),val preset: String = "",val version: Int = 0)
internal data class ManagedPreferenceRead(val values: Map<String,String>,val origins: Map<String,String>,val state: ManagedPreferenceState)
internal fun validManagedPreferenceValues(values: Map<String,String>): Boolean = values.all { (key,value) -> managedPreferenceRules[key]?.choices?.contains(value) == true }
internal fun readManagedPreferences(prefs: Preferences, profiles: ProfileManager, profile: String): ManagedPreferenceRead {
    val stateKey=profiles.profileStringKeyFor(profile,"managed_preferences_v1")
    val state=runCatching { Gson().fromJson(prefs[stateKey],ManagedPreferenceState::class.java)?.takeIf {
        validManagedPreferenceValues(it.base)&&validManagedPreferenceValues(it.overrides)&&validManagedPreferenceValues(it.applied)&&it.prior.keys.all(managedPreferenceRules::containsKey)
    } }.getOrNull() ?: ManagedPreferenceState()
    val values=managedPreferenceRules.mapValues { (key,rule) ->
        if(rule.boolean) prefs[profiles.profileBooleanKeyFor(profile,key)]?.toString() ?: rule.default
        else prefs[profiles.profileStringKeyFor(profile,key)] ?: rule.default
    }
    val origins=values.mapValues { (key,value) -> when {
        value!=state.applied[key] -> "local"
        state.overrides.containsKey(key) -> "device_profile_override"
        state.base.containsKey(key) -> "base_preset"
        else -> "local"
    } }
    return ManagedPreferenceRead(values,origins,state)
}
internal fun applyManagedPreferences(prefs: MutablePreferences,profiles: ProfileManager,profile: String,base: Map<String,String>,overrides: Map<String,String>,preset: String,version: Int) {
    require(validManagedPreferenceValues(base)&&validManagedPreferenceValues(overrides))
    require((preset.isEmpty()&&version==0&&base.isEmpty())||(preset.matches(Regex("[a-f0-9]{32}"))&&version in 1..100000))
    val before=readManagedPreferences(prefs,profiles,profile)
    val prior=before.state.prior.toMutableMap();val effective=base+overrides
    val applied=mutableMapOf<String,String>()
    fun raw(key: String,rule: ManagedPreferenceRule): String? = if(rule.boolean) prefs[profiles.profileBooleanKeyFor(profile,key)]?.toString() else prefs[profiles.profileStringKeyFor(profile,key)]
    fun write(key: String,rule: ManagedPreferenceRule,value: String?) {
        if(rule.boolean) {val k=profiles.profileBooleanKeyFor(profile,key);if(value==null)prefs.remove(k) else prefs[k]=value=="true"}
        else {val k=profiles.profileStringKeyFor(profile,key);if(value==null)prefs.remove(k) else prefs[k]=value}
    }
    for((key,rule) in managedPreferenceRules) {
        if(effective.containsKey(key)) {
            if(!prior.containsKey(key)||!before.state.applied.containsKey(key)||before.values[key]!=before.state.applied[key])prior[key]=raw(key,rule)
            write(key,rule,effective.getValue(key));applied[key]=effective.getValue(key)
        } else if(before.state.applied.containsKey(key)) {
            // Reset only a value still owned by the managed layer; do not erase a
            // newer local edit just because an old override was removed remotely.
            if(before.values[key]==before.state.applied[key])write(key,rule,prior[key])
            prior.remove(key)
        }
    }
    prefs[profiles.profileStringKeyFor(profile,"managed_preferences_v1")]=Gson().toJson(ManagedPreferenceState(base,overrides,prior,applied,preset,version))
}

private fun writeManagedRaw(prefs: MutablePreferences,profiles: ProfileManager,profile: String,key: String,value: String?) {
    if(managedPreferenceRules.getValue(key).boolean) {val k=profiles.profileBooleanKeyFor(profile,key);if(value==null)prefs.remove(k) else prefs[k]=value=="true"}
    else {val k=profiles.profileStringKeyFor(profile,key);if(value==null)prefs.remove(k) else prefs[k]=value}
}
/** Export the underlying user choices, never this installation's managed overlay. */
internal fun withoutManagedPreferenceOverlays(prefs: Preferences,profiles: ProfileManager,ids: List<String>): Preferences {
    val copy=prefs.toMutablePreferences()
    for(profile in ids.distinct()) {
        val read=readManagedPreferences(prefs,profiles,profile)
        for((key,value) in read.state.applied)if(read.values[key]==value)writeManagedRaw(copy,profiles,profile,key,read.state.prior[key])
    }
    return copy.toPreferences()
}
/** Both halves run inside the SAME Cloud import DataStore edit. */
internal fun beforeManagedPreferenceCloudImport(prefs: MutablePreferences,profiles: ProfileManager,profile: String): ManagedPreferenceState {
    val read=readManagedPreferences(prefs,profiles,profile)
    val active=read.state.applied.filter { (key,value)->read.values[key]==value }
    active.keys.forEach { key->writeManagedRaw(prefs,profiles,profile,key,read.state.prior[key]) }
    return read.state.copy(applied=active)
}
internal fun afterManagedPreferenceCloudImport(prefs: MutablePreferences,profiles: ProfileManager,profile: String,state: ManagedPreferenceState) {
    if(state.applied.isEmpty()&&state.base.isEmpty()&&state.overrides.isEmpty())return
    val prior=state.prior.toMutableMap()
    for((key,value) in state.applied) {
        val rule=managedPreferenceRules.getValue(key)
        prior[key]=if(rule.boolean)prefs[profiles.profileBooleanKeyFor(profile,key)]?.toString() else prefs[profiles.profileStringKeyFor(profile,key)]
        writeManagedRaw(prefs,profiles,profile,key,value)
    }
    prefs[profiles.profileStringKeyFor(profile,"managed_preferences_v1")]=Gson().toJson(state.copy(prior=prior))
}
