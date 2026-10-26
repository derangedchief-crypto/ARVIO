package com.arflix.tv.access

import android.os.SystemClock
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fixed-origin validator, isolated from playback/logging interceptors. */
internal class PrimaryLineVerifier {
    private val client=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(7,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun check(anchor:ActivationAnchor):LineDecision=withContext(Dispatchers.IO){
        val provider=provider(anchor)
        if(provider==LineDecision.Invalid)return@withContext provider
        val began=SystemClock.elapsedRealtime()
        val worker=worker(anchor)
        if(worker==LineDecision.Invalid)return@withContext worker
        if(provider is LineDecision.Valid&&worker is LineDecision.Valid){
            val remaining=minOf(worker.seconds,provider.seconds-(SystemClock.elapsedRealtime()-began+999)/1000)
            return@withContext if(remaining>0)LineDecision.Valid(remaining) else LineDecision.Invalid
        }
        LineDecision.Unknown
    }
    private suspend fun provider(anchor:ActivationAnchor):LineDecision=withContext(Dispatchers.IO){
        val began=SystemClock.elapsedRealtime()
        try{
            val url="$PRIMARY_HOST/player_api.php".toHttpUrl().newBuilder().addQueryParameter("username",anchor.username).addQueryParameter("password",anchor.password).build()
            client.newCall(Request.Builder().url(url).header("Cache-Control","no-store").build()).execute().use{r->
                val source=r.body?.source()?:return@withContext LineDecision.Unknown
                source.request(65537);if(source.buffer.size>65536)return@withContext LineDecision.Unknown
                val json=JSONObject(source.readUtf8())
                val decision=parsePrimaryDecision(json,r.header("Date"),SystemClock.elapsedRealtime()-began)
                if(!r.isSuccessful&&decision is LineDecision.Valid)LineDecision.Unknown else decision
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){LineDecision.Unknown}
    }
    private suspend fun worker(anchor:ActivationAnchor):LineDecision=withContext(Dispatchers.IO){
        val began=SystemClock.elapsedRealtime()
        try{
            val body=JSONObject().put("username",anchor.username).put("password",anchor.password).toString()
            val request=Request.Builder().url("https://activationworker.extremeiptv.net/management/primary-verify")
                .header("Cache-Control","no-store").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use{r->
                if(!r.isSuccessful)return@withContext LineDecision.Unknown
                val source=r.body?.source()?:return@withContext LineDecision.Unknown
                source.request(4097);if(source.buffer.size>4096)return@withContext LineDecision.Unknown
                parseWorkerPrimary(JSONObject(source.readUtf8()),SystemClock.elapsedRealtime()-began)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){LineDecision.Unknown}
    }
}
