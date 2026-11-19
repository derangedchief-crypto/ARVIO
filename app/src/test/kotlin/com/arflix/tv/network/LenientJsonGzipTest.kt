package com.arflix.tv.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class LenientJsonGzipTest {
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun response(bytes: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/catalogue").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", "application/json")
        .header("Content-Encoding", "gzip")
        .header("Content-Length", bytes.size.toString())
        .body(bytes.toResponseBody("application/json".toMediaType())).build()

    @Test fun `plain and up to three gzip layers decode without losing bytes`() {
        val json = "{\"items\":[1,2,3]}".toByteArray()
        var bytes = json
        repeat(4) {
            decodeLenientJsonGzip(response(bytes)).use { decoded ->
                assertNull(decoded.header("Content-Encoding"))
                assertNull(decoded.header("Content-Length"))
                assertEquals(-1L, decoded.body!!.contentLength())
                assertArrayEquals(json, decoded.body!!.bytes())
            }
            bytes = gzip(bytes)
        }
    }

    @Test fun `large expansion stays streamed and closing releases the input`() {
        // Reproduces the 64MB+ allocation path without allocating the expanded body.
        val block = ByteArray(8192) { ' '.code.toByte() }
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gzip -> repeat(10240) { gzip.write(block) } }
        }.toByteArray()
        var closed = false
        val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = input
        }
        decodeLenientJsonGzip(response(bytes).newBuilder().body(body).build()).use { decoded ->
            val source = decoded.body!!.source()
            assertTrue("Decoding must not eagerly expand 80MB", source.buffer.size <= 8192L)
            var total = 0L
            val sink = Buffer()
            while (true) {
                val read = source.read(sink, 8192)
                if (read == -1L) break
                total += read
                sink.clear()
            }
            assertEquals(80L * 1024 * 1024, total)
        }
        assertTrue(closed)
    }

    @Test fun `truncated gzip fails as IO rather than returning a partial success`() {
        val bytes = gzip("{\"items\":[]}".toByteArray()).dropLast(4).toByteArray()
        assertThrows(IOException::class.java) {
            decodeLenientJsonGzip(response(bytes)).use { it.body!!.string() }
        }
    }

    @Test fun `excessive nesting is rejected`() {
        var bytes = "{}".toByteArray()
        repeat(4) { bytes = gzip(bytes) }
        assertThrows(IOException::class.java) { decodeLenientJsonGzip(response(bytes)) }
    }

    @Test fun `non JSON bodies are left to the normal HTTP decoder`() {
        val original = response(gzip("image".toByteArray())).newBuilder()
            .header("Content-Type", "image/png").build()
        original.use { assertSame(original, decodeLenientJsonGzip(original)) }
    }
}
