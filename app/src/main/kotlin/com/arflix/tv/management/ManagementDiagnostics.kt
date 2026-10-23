package com.arflix.tv.management

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Fixed enums/counters only. Never inspect interfaces, SSIDs, addresses or files. */
internal fun managementRuntimeDiagnostics(context: Context): Map<String, String> {
    val runtime = Runtime.getRuntime()
    val result = mutableMapOf(
        "diagnostics_version" to "2",
        "heap_used" to (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0).toString(),
        "heap_limit" to runtime.maxMemory().toString(),
        "memory_state" to "unknown",
        "network_state" to "unknown",
        "network_transport" to "unknown",
        "network_metered" to "unknown"
    )
    try {
        context.getSystemService(ActivityManager::class.java)?.let { manager ->
            val memory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            result["memory_state"] = if (memory.lowMemory) "low" else "normal"
        }
    } catch (_: Exception) { /* Missing/unsupported service is not healthy by default. */ }
    try {
        context.getSystemService(ConnectivityManager::class.java)?.let { manager ->
            val network = manager.activeNetwork
            if (network == null) {
                result["network_state"] = "offline"
                result["network_transport"] = "none"
            } else {
                manager.getNetworkCapabilities(network)?.let { caps ->
                    result["network_state"] = when {
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "validated"
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "unvalidated"
                        else -> "local_only"
                    }
                    result["network_transport"] = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        else -> "other"
                    }
                    result["network_metered"] = if (manager.isActiveNetworkMetered) "yes" else "no"
                }
            }
        }
    } catch (_: SecurityException) {
        result["network_state"] = "permission_denied"
        result["network_transport"] = "unknown"
        result["network_metered"] = "unknown"
    } catch (_: Exception) { /* Never upload platform exception text. */ }
    return result
}
