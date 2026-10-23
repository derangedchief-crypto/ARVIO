package com.arflix.tv.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

internal fun normalizedLivePrebuffer(value: String?): Int = value?.toIntOrNull()?.takeIf { it in listOf(0, 2, 5, 10) } ?: 0

/** Called only inside one DataStore edit. No source/profile/favourite/guide writes. */
internal fun compareManagedLivePrebuffer(prefs: MutablePreferences, key: Preferences.Key<String>, profileIsCurrent: Boolean,
    accountMatches: Boolean, expected: Int, requested: Int): Boolean {
    require(expected in listOf(0, 2, 5, 10) && requested in listOf(0, 2, 5, 10))
    if (!profileIsCurrent || !accountMatches || normalizedLivePrebuffer(prefs[key]) != expected) return false
    prefs[key] = requested.toString()
    return true
}
