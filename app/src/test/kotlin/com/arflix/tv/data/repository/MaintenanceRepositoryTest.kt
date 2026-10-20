package com.arflix.tv.data.repository

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class MaintenanceRepositoryTest {
    @Test fun explicitBooleanIsRequiredSoBadResponsesCannotUnlockMaintenance() {
        listOf("{}", "{\"active\":\"false\"}", "<html>offline</html>", "{\"active\":null}")
            .forEach { raw -> assertTrue(runCatching { parseMaintenanceStatus(raw) }.isFailure) }
    }

    @Test fun maintenanceMessageIsBoundedAndBlankUsesDefault() {
        assertEquals(500, parseMaintenanceStatus("""{"active":true,"message":"${"x".repeat(600)}"}""").message.length)
        assertEquals(MaintenanceState().message, parseMaintenanceStatus("""{"active":true,"message":" "}""").message)
    }

    @Test fun enabledStatePersistsAcrossRestartAndOutagesUntilExplicitlyDisabled() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("hub_maintenance", 0)
        prefs.edit().clear().commit()
        // Auth data is intentionally separate and must not be modified.
        val auth = context.getSharedPreferences("fixture_auth", 0)
        auth.edit().putString("session", "still-signed-in").commit()
        var body = """{"active":true,"message":"Updating services"}"""
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(MaintenanceRepository.STATUS_URL, chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("Fixture").body(body.toResponseBody()).build()
        }.build()
        fun repo(): MaintenanceRepository = MaintenanceRepository(context).also {
            MaintenanceRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, client)
        }
        val first = repo()
        assertTrue(first.state.value.checking)
        first.refresh()
        assertTrue(first.state.value.active)
        assertFalse(first.state.value.checking)
        val restarted = repo()
        assertTrue(restarted.state.value.active)
        code = 503
        restarted.refresh()
        assertTrue(restarted.state.value.active)
        assertTrue(restarted.state.value.connectionFailed)
        assertEquals("Updating services", restarted.state.value.message)
        code = 200
        body = """{"active":"false"}"""
        restarted.refresh()
        assertTrue(restarted.state.value.active)
        body = """{"active":false,"message":"Back online"}"""
        restarted.refresh()
        assertFalse(restarted.state.value.active)
        assertFalse(repo().state.value.active)
        assertEquals("still-signed-in", auth.getString("session", null))
    }

    @Test fun unavailableUnconfiguredServiceDoesNotLockOutFirstRunCustomers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hub_maintenance", 0).edit().clear().commit()
        val repo = MaintenanceRepository(context)
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Offline") }.build()
        MaintenanceRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repo, client)
        repo.refresh()
        assertFalse(repo.state.value.active)
        assertFalse(repo.state.value.checking)
        assertTrue(repo.state.value.connectionFailed)
    }
}
