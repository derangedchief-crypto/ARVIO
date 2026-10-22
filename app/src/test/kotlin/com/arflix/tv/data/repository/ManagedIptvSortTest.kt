package com.arflix.tv.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class ManagedIptvSortTest {
    private val key = stringPreferencesKey("profile_one_sort")
    @Test fun updateTouchesOnlyTheSelectedSortKey() {
        val other = stringPreferencesKey("profile_two_sort")
        val source = stringPreferencesKey("profile_one_playlist")
        val prefs = mutablePreferencesOf(key to "provider", other to "number", source to "synthetic-private-source")
        assertTrue(compareManagedSort(prefs, key, true, true, "provider", "name"))
        assertEquals("name", prefs[key]); assertEquals("number", prefs[other]); assertEquals("synthetic-private-source", prefs[source])
        assertEquals(3, prefs.asMap().size)
    }
    @Test fun localEditProfileSwitchAndSourceAccountSwitchAllRejectWithoutWriting() {
        val prefs = mutablePreferencesOf(key to "number")
        assertFalse(compareManagedSort(prefs, key, true, true, "provider", "name"))
        assertFalse(compareManagedSort(prefs, key, false, true, "number", "name"))
        assertFalse(compareManagedSort(prefs, key, true, false, "number", "name"))
        assertEquals("number", prefs[key])
    }
    @Test fun missingSortUsesProviderDefaultAndUnknownValuesAreNeverApplied() {
        val prefs = mutablePreferencesOf()
        assertTrue(compareManagedSort(prefs, key, true, true, "provider", "name"))
        assertTrue(runCatching { compareManagedSort(prefs, key, true, true, "name", "https://arbitrary.example") }.isFailure)
        assertEquals("name", prefs[key])
    }
}
