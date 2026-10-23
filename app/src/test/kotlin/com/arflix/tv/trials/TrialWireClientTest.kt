package com.arflix.tv.trials

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class TrialWireClientTest {
    private val brand="a".repeat(32)
    private fun client(reply: (String,JSONObject?)->String): OkHttpClient=OkHttpClient.Builder().addInterceptor { chain ->
        val r=chain.request();assertEquals("https",r.url.scheme);assertEquals("control.fecxbrfzku.net",r.url.host)
        val body=r.body?.let { b->val out=Buffer();b.writeTo(out);JSONObject(out.readUtf8()) }
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("SYNTHETIC").body(reply(r.url.encodedPath.substringAfterLast('/'),body).toResponseBody("application/json".toMediaType())).build()
    }.build()
    @Test fun `generic app never asks for an offer or session`() {
        var calls=0;val wire=TrialWireClient(client { _,_->calls++;"{}" },"")
        assertFalse(wire.offer().available);assertEquals(0,calls)
    }
    @Test fun `contact normalization requires country code with no verification step`() {
        val c=TrialContact.parse("  Jose\u0301  ","001 (555) 123-4567")
        assertEquals("José",c.firstName);assertEquals("+15551234567",c.phone)
        try { TrialContact.parse("Name","5551234567");fail() }catch(_:IllegalArgumentException){}
        assertEquals(64,c.requestHash().length)
    }
    @Test fun `client signs only the exact server assigned operation and never selects reseller or package`() {
        val c=TrialContact.parse("Test","+15551234567");val token="b".repeat(64);val nonce="c".repeat(64);val op="d".repeat(32)
        val expected="trial-submit-v1\n${trialHash(token)}\n$brand\n$op\n$nonce\n${c.requestHash()}"
        var calls=0
        val wire=TrialWireClient(client { path,body ->
            calls++;if(body!=null){assertFalse(body.has("reseller_id"));assertFalse(body.has("package_id"));assertFalse(body.has("price"))}
            when(path){
                "session"->JSONObject().put("token",token).put("nonce",nonce).put("operation_id",op).put("signing_text",expected).put("expires_seconds",900).toString()
                "submit"->{assertEquals("+15551234567",body!!.getString("phone"));assertTrue(body.getBoolean("notice_accepted"));JSONObject().put("state","prepared").put("activation_code","123456").put("expires_at",System.currentTimeMillis()/1000+3600).toString()}
                else->error("Unexpected route")
            }
        },brand)
        val s=wire.session(c,"e".repeat(64),"SYNTHETIC-PUBLIC-KEY","f".repeat(64)){text->assertEquals(expected,text);"c2ln"}
        assertEquals("123456",wire.submit(s,c).activationCode);assertEquals(2,calls)
    }
    @Test fun `malformed binding is rejected before using the private signing key`() {
        var signed=false;val wire=TrialWireClient(client { _,_->JSONObject().put("token","b".repeat(64)).put("nonce","c".repeat(64)).put("operation_id","d".repeat(32)).put("signing_text","sign something else").put("expires_seconds",900).toString() },brand)
        try{wire.session(TrialContact.parse("Test","+15551234567"),"e".repeat(64),"KEY","f".repeat(64)){signed=true;"sig"};fail()}catch(_:IllegalArgumentException){}
        assertFalse(signed)
    }
    @Test fun `oversized response is bounded and does not become a cached credential document`() {
        val wire=TrialWireClient(client { _,_->"x".repeat(70000) },brand)
        try{wire.offer();fail()}catch(_:IllegalArgumentException){}
    }
}
