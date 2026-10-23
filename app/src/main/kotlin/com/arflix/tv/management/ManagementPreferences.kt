package com.arflix.tv.management

import androidx.annotation.Keep
import com.arflix.tv.data.repository.ManagedPreferenceRead
import com.arflix.tv.data.repository.managedPreferenceRules
import com.google.gson.Gson

@Keep internal data class ManagedPreferencesDocument(val schema_version: Int = 1,val profile_ref: String,val revision: String,
    val values: Map<String,String>,val origins: Map<String,String>,val base_values: Map<String,String>,val overrides: Map<String,String>,val base_preset_id: String,val base_version: Int)
internal fun managedPreferencesDocument(secret: String,profile: String,scope: String,read: ManagedPreferenceRead): ManagedPreferencesDocument =
    ManagedPreferencesDocument(profile_ref=managementHash(profile),revision=managementPrivateMac(secret,"preferences-v1\n$profile\n$scope\n"+Gson().toJson(read)),
        values=read.values.mapValues { (key,value)->if(value in managedPreferenceRules.getValue(key).choices)value else "local_custom" },
        origins=read.origins,base_values=read.state.base,overrides=read.state.overrides,base_preset_id=read.state.preset,base_version=read.state.version)
