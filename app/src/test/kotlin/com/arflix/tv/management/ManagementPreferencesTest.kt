package com.arflix.tv.management

import androidx.datastore.preferences.core.*
import com.arflix.tv.data.repository.*
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class ManagementPreferencesTest {
    private fun profiles():ProfileManager = mockk<ProfileManager>().also { p->
        every { p.profileStringKeyFor(any(),any()) } answers { stringPreferencesKey("${firstArg<String>()}:${secondArg<String>()}") }
        every { p.profileBooleanKeyFor(any(),any()) } answers { booleanPreferencesKey("${firstArg<String>()}:${secondArg<String>()}") }
    }
    @Test fun explicitOverrideWinsBaseAndResetRestoresUnderlyingLocalValue() {
        val profiles=profiles();val key=profiles.profileStringKeyFor("one","clock_format");val prefs=mutablePreferencesOf(key to "12h")
        applyManagedPreferences(prefs,profiles,"one",mapOf("clock_format" to "24h"),mapOf("clock_format" to "12h"),"a".repeat(32),1)
        assertEquals("device_profile_override",readManagedPreferences(prefs,profiles,"one").origins["clock_format"])
        applyManagedPreferences(prefs,profiles,"one",mapOf("clock_format" to "24h"),emptyMap(),"a".repeat(32),1)
        assertEquals("24h",prefs[key]);assertEquals("base_preset",readManagedPreferences(prefs,profiles,"one").origins["clock_format"])
        applyManagedPreferences(prefs,profiles,"one",emptyMap(),emptyMap(),"",0)
        assertEquals("12h",prefs[key])
    }
    @Test fun clearingManagedPolicyPreservesNewerLocalEditAndUnrelatedSecrets() {
        val profiles=profiles();val key=profiles.profileStringKeyFor("one","clock_format");val secret=stringPreferencesKey("login_secret")
        val prefs=mutablePreferencesOf(key to "12h",secret to "private-password")
        applyManagedPreferences(prefs,profiles,"one",emptyMap(),mapOf("clock_format" to "24h"),"",0)
        prefs[key]="12h"
        applyManagedPreferences(prefs,profiles,"one",emptyMap(),emptyMap(),"",0)
        assertEquals("12h",prefs[key]);assertEquals("private-password",prefs[secret])
    }
    @Test fun cloudNeverExportsDeviceOverrideAndIncomingCloudUpdatesUnderlyingChoice() {
        val profiles=profiles();val key=profiles.profileStringKeyFor("one","clock_format");val other=profiles.profileStringKeyFor("two","clock_format")
        val prefs=mutablePreferencesOf(key to "12h",other to "12h")
        applyManagedPreferences(prefs,profiles,"one",emptyMap(),mapOf("clock_format" to "24h"),"",0)
        assertEquals("12h",withoutManagedPreferenceOverlays(prefs,profiles,listOf("one","two"))[key])
        assertEquals("24h",prefs[key])
        val overlay=beforeManagedPreferenceCloudImport(prefs,profiles,"one");prefs[key]="12h";afterManagedPreferenceCloudImport(prefs,profiles,"one",overlay)
        assertEquals("24h",prefs[key]);assertEquals("12h",prefs[other])
        applyManagedPreferences(prefs,profiles,"one",emptyMap(),emptyMap(),"",0);assertEquals("12h",prefs[key])
    }
    @Test fun localCustomValuesAreNotUploadedAndRevisionBindsScopeAndActualLocalState() {
        val profiles=profiles();val key=profiles.profileStringKeyFor("one","default_subtitle");val prefs=mutablePreferencesOf(key to "https://private.test/password")
        val read=readManagedPreferences(prefs,profiles,"one");val doc=managedPreferencesDocument("a".repeat(64),"one","scope",read)
        assertEquals("local_custom",doc.values["default_subtitle"]);assertFalse(doc.toString().contains("private.test"))
        assertNotEquals(doc.revision,managedPreferencesDocument("a".repeat(64),"one","different",read).revision)
        assertFalse(validManagedPreferenceValues(mapOf("update_url" to "https://other.test")))
    }
}
