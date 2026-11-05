package com.extreme.myhub

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WorkflowTest {
    private val device = "d".repeat(32)
    private val session = Session("a".repeat(64), "b".repeat(64), "Reseller")
    @Test fun `process recreation retains exact command body and idempotency key`() = runBlocking {
        val original = Mutation.create("devices/$device/commands", "devices/$device/preferences", JSONObject().put("kind", "apply_preferences").put("payload", JSONObject().put("draft_id", "e".repeat(32))))
        val recovered = Mutation.decode(original.encode())
        assertEquals(original, recovered)
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/"))
            listOf(original, recovered).forEach { pending ->
                server.enqueue(MockResponse().setBody("{\"status\":\"pending\"}"))
                client.request("manage/${pending.route}", session, JSONObject(pending.body!!), idempotency = pending.key)
                val request = server.takeRequest()
                assertEquals(original.key, request.getHeader("Idempotency-Key"))
                assertEquals(original.body, request.body.readUtf8())
            }
        }
    }
    @Test fun `uncertain non-idempotent writes retain no merchant or source credentials`() {
        listOf("renewals/connection", "devices/$device/sources/preview", "password", "addons/redeem").forEach { route ->
            val pending = Mutation.create(route, "overview", JSONObject().put("secret", "do-not-retain"))
            assertNull(pending.body); assertNull(pending.key)
            assertFalse(pending.encode().contains("do-not-retain"))
        }
    }
    @Test fun `source editing preserves private fields unless explicitly replaced`() {
        val row = JSONObject().put("ref", "a".repeat(64)).put("kind", "m3u").put("name", "Source").put("enabled", true).put("canonical", true).put("guide_count", 2)
        val changed = SourceForms.entry(SourceForms.replacements(row, mapOf("name" to "Renamed", "enabled" to "yes", "replace_url" to "", "guides_action" to "keep")))
        assertEquals(setOf("ref", "kind", "name", "enabled"), changed.keys().asSequence().toSet())
        val cleared = SourceForms.replacements(row, mapOf("name" to "Source", "enabled" to "yes", "guides_action" to "replace", "guides" to ""))
        assertEquals(0, cleared.getJSONArray("replace_guides").length())
    }
    @Test fun `renewal prices preserve country package and stale revision guards`() {
        val body = RenewalForms.price(JSONObject().put("id", 42), JSONObject().put("id", "p").put("revision", 7), "CA", "12.34", "CAD", true)
        assertEquals(42, body.getInt("package_id")); assertEquals(7, body.getInt("expected_revision")); assertEquals("12.34", body.getString("amount"))
        assertEquals("CA", body.getString("country")); assertEquals("CAD", body.getString("currency"))
        try { RenewalForms.routing("CA", 4, listOf("rampex")); fail("Unsupported Canadian gateway") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `stale server revision is exposed without a second write`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"Refresh revision\"}"))
            try { HubClient(server.url("/")).request("manage/device-defaults", session, JSONObject().put("expected_revision", 1)); fail("Stale revision accepted") } catch (e: HubFailure) { assertEquals(409, e.status) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `private downloads authenticate and refuse redirects and cross-tenant denials`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/")); val route = "devices/$device/support/${"e".repeat(32)}/download"
            for (status in listOf(302, 403, 404)) {
                server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", "https://other.example/file"))
                val out = ByteArrayOutputStream()
                try { client.download(route, session, out); fail("Download should fail") } catch (e: HubFailure) { assertEquals(status, e.status) }
                assertEquals(0, out.size()); assertEquals("Bearer ${session.token}", server.takeRequest().getHeader("Authorization"))
            }
            assertEquals(3, server.requestCount)
            try { client.download("https://other.example/file", session, ByteArrayOutputStream()); fail("External download allowed") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `public APK downloads omit bearer and verify expected digest`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/")); val route = "/downloads/apps/$device/Brand-1.0.apk"
            val body = "apk-fixture"; val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse().setBody(body)); val output = ByteArrayOutputStream(); client.download(route, session, output, digest)
            assertEquals(body, output.toString()); assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("corrupted"))
            try { client.download(route, session, ByteArrayOutputStream(), digest); fail("Corrupt APK accepted") } catch (e: HubFailure) { assertEquals(503, e.status) }
        }
    }
    @Test fun `layout templates retain unmatched categories and reject ambiguous matches`() {
        fun row(ref: String, name: String, hidden: Boolean) = JSONObject().put("ref", ref).put("match_name", name).put("hidden", hidden)
        val catalog = JSONObject().put("pages", 1).put("total", 3).put("items", org.json.JSONArray(listOf(row("a", "News", false), row("b", "Sports", true), row("c", "Films", true))))
        val entry = JSONObject().put("name", "Films").put("hidden", false)
        val result = LayoutForms.template(catalog, listOf(entry)).rows()
        assertEquals(listOf("c", "a", "b"), result.map { it.getString("ref") })
        assertFalse(result[0].getBoolean("hidden")); assertTrue(result[2].getBoolean("hidden"))
        catalog.getJSONArray("items").getJSONObject(1).put("match_name", "News")
        try { LayoutForms.template(catalog, listOf(entry)); fail("Ambiguous categories accepted") } catch (_: IllegalArgumentException) { }
    }
}
