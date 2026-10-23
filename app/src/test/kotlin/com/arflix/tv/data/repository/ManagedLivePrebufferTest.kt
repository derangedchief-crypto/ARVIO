package com.arflix.tv.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class ManagedLivePrebufferTest {
    private val key = stringPreferencesKey("iptv_device_live_prebuffer_seconds")
    @Test fun absentOrInvalidValuesPreserveFastOffBaseline() {
        for (value in listOf(null, "", "1", "60", "-1", "private-url")) assertEquals(0, normalizedLivePrebuffer(value))
        for (value in listOf(0, 2, 5, 10)) assertEquals(value, normalizedLivePrebuffer(value.toString()))
    }
    @Test fun atomicPreferenceChangeTouchesOnlyDeviceBufferAndOffIsReversible() {
        val login = stringPreferencesKey("login")
        val favourites = stringPreferencesKey("favourites")
        val prefs = mutablePreferencesOf(login to "private-login", favourites to "saved-list")
        assertTrue(compareManagedLivePrebuffer(prefs, key, true, true, 0, 5))
        assertEquals("5", prefs[key]); assertEquals("private-login", prefs[login]); assertEquals("saved-list", prefs[favourites])
        assertTrue(compareManagedLivePrebuffer(prefs, key, true, true, 5, 0))
        assertEquals("0", prefs[key])
    }
    @Test fun staleProfileAccountOrValueDoesNotWrite() {
        val prefs = mutablePreferencesOf(key to "2")
        assertFalse(compareManagedLivePrebuffer(prefs, key, false, true, 2, 10))
        assertFalse(compareManagedLivePrebuffer(prefs, key, true, false, 2, 10))
        assertFalse(compareManagedLivePrebuffer(prefs, key, true, true, 0, 10))
        assertEquals("2", prefs[key])
    }
    @Test(expected = IllegalArgumentException::class) fun unboundedSettingIsRefused() {
        compareManagedLivePrebuffer(mutablePreferencesOf(), key, true, true, 0, 1000)
    }
}
