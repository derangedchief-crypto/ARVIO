package com.arflix.tv.network

import java.io.IOException
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.GzipSource
import okio.buffer

/** Some providers send plain JSON or nested gzip with a single gzip header.
 * Decode incrementally: compressed size does not bound expanded heap usage. */
internal fun decodeLenientJsonGzip(response: Response): Response {
    if (!response.header("Content-Encoding").equals("gzip", ignoreCase = true) ||
        !response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)
    ) return response
    val body = response.body ?: return response
    var source = body.source()
    try {
        repeat(3) {
            if (source.hasGzipMagic()) source = GzipSource(source).buffer()
        }
        if (source.hasGzipMagic()) throw IOException("Too many compressed JSON layers")
    } catch (error: IOException) {
        source.close()
        throw error
    }
    val decoded = source
    return response.newBuilder()
        .removeHeader("Content-Encoding")
        .removeHeader("Content-Length")
        .body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = -1L
            override fun source() = decoded
        })
        .build()
}

private fun BufferedSource.hasGzipMagic(): Boolean =
    request(2) && buffer.getByte(0).toInt() and 0xff == 0x1f &&
        buffer.getByte(1).toInt() and 0xff == 0x8b
