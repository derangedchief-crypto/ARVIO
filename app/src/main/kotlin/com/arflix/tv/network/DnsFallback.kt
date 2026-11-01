package com.arflix.tv.network

import java.net.InetAddress
import java.net.UnknownHostException

/** Only DNS failures and OkHttp's specific missing-PSL resource failure may
 * fall back to system DNS. TLS/hostname verification is never changed. */
internal fun lookupDnsWithFallback(
    customProvider: Boolean,
    selected: () -> List<InetAddress>,
    system: () -> List<InetAddress>,
): List<InetAddress> {
    try {
        return selected()
    } catch (failure: Exception) {
        val missingSuffix = failure is IllegalStateException &&
            failure.message == "Unable to load PublicSuffixDatabase.list resource."
        if (!customProvider || (failure !is UnknownHostException && !missingSuffix)) throw failure
        try {
            return system()
        } catch (fallback: UnknownHostException) {
            // Preserve the original DNS failure, but never convert a missing
            // resource into a fatal IllegalStateException if both DNS paths fail.
            if (failure is UnknownHostException) throw failure
            throw fallback
        }
    }
}
