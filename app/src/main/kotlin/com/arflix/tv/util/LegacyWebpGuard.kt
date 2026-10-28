package com.arflix.tv.util

import android.os.Build
import coil.ImageLoader
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import java.io.IOException

/** Fire OS / Android 9 reports a native WebPRescalerImport SIGSEGV. A Java catch
 * cannot recover from SIGSEGV. Refuse WebP before invoking that platform decoder;
 * Coil uses the normal placeholder/error path instead. Other formats/platforms
 * are untouched. This is a targeted mitigation, not proof of the native cause.
 */
internal class LegacyWebpGuard : Decoder.Factory {
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        if (!isAffectedPlatform(Build.VERSION.SDK_INT,Build.MANUFACTURER)) return null
        val header = result.source.source().peek()
        if (!header.request(12)) return null
        val bytes = header.readByteArray(12)
        if (!isWebp(bytes)) return null
        return Decoder { throw IOException("WebP decoding is unavailable on this legacy Fire TV platform.") }
    }

    companion object {
        internal fun isAffectedPlatform(sdk: Int, manufacturer: String) = sdk <= 28 && manufacturer.equals("Amazon",true)
        internal fun isWebp(bytes: ByteArray): Boolean = bytes.size >= 12 &&
            bytes[0]=='R'.code.toByte() && bytes[1]=='I'.code.toByte() && bytes[2]=='F'.code.toByte() && bytes[3]=='F'.code.toByte() &&
            bytes[8]=='W'.code.toByte() && bytes[9]=='E'.code.toByte() && bytes[10]=='B'.code.toByte() && bytes[11]=='P'.code.toByte()
    }
}
