package com.extreme.myhub

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HubClientTest {
    private val session = Session("a".repeat(64), "b".repeat(64), "Fixture reseller")
    @Test fun `login sends panel username and password without requiring an email`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(JSONObject().put("token", session.token).put("csrf", session.csrf).put("user", JSONObject().put("role", "reseller").put("display_name", "Panel reseller")).toString()))
            val result = HubClient(server.url("/")).login(" panel-user ", " panel password ")
            assertEquals(session.token, result.token)
            val request = server.takeRequest(); assertEquals("/api/my-hub/login", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("username", "password"), body.keys().asSequence().toSet())
            assertEquals("panel-user", body.getString("username")); assertEquals(" panel password ", body.getString("password"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun `native management uses bearer and CSRF without web cookie or client tenant`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/"))
            server.enqueue(MockResponse().setBody("{\"id\":\"record\"}"))
            client.request("manage/customers", session, JSONObject().put("name", "Customer"))
            val request = server.takeRequest()
            assertEquals("/api/my-hub/manage/customers", request.path)
            assertEquals("Bearer ${session.token}", request.getHeader("Authorization"))
            assertEquals(session.csrf, request.getHeader("X-CSRF-Token"))
            assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(setOf("name"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
        }
    }
    @Test fun `redirect never forwards credentials to another host`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/path"))
            try { client.request("license", session); fail("Redirect accepted") } catch (e: HubFailure) { assertEquals(302, e.status) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `command retries preserve caller supplied idempotency key`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/")); val key = "c".repeat(32)
            repeat(2) {
                server.enqueue(MockResponse().setBody("{\"status\":\"pending\"}"))
                client.request("manage/devices/${"d".repeat(32)}/commands", session, JSONObject().put("kind", "refresh_playlist"), idempotency = key)
                assertEquals(key, server.takeRequest().getHeader("Idempotency-Key"))
            }
        }
    }
    @Test fun `license revocation remains a server rejection`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = HubClient(server.url("/"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"error\":\"Activate My Hub\"}"))
            try { client.request("manage/overview", session); fail("Inactive license accepted") } catch (e: HubFailure) { assertEquals(403, e.status) }
        }
    }
}
