package com.arflix.tv.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/** Called INSIDE the settings DataStore edit; no URL/profile/favourite keys written. */
internal fun compareManagedSort(prefs: MutablePreferences, key: Preferences.Key<String>, profileIsCurrent: Boolean,
    sourceAccountMatches: Boolean, expected: String, requested: String): Boolean {
    require(expected in listOf("provider", "number", "name") && requested in listOf("provider", "number", "name"))
    if (!profileIsCurrent || !sourceAccountMatches || normalizeIptvSortOrder(prefs[key]) != expected) return false
    prefs[key] = requested
    return true
}
