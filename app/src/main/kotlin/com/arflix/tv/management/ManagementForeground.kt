package com.arflix.tv.management

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only a RESUMED MainActivity binds this bridge. No background activity starts. */
interface ManagementForegroundHost {
    fun canReopen(): Boolean
    fun reopen(): Boolean
    suspend fun captureSupportPanel(expiresAtMs: Long,eligible: ()->Boolean): ByteArray? = null
}

object ManagementForeground {
    private var host: ManagementForegroundHost? = null // main-thread only
    fun bind(value: ManagementForegroundHost) { host = value }
    fun unbind(value: ManagementForegroundHost) { if (host === value) host = null }
    internal suspend fun eligible(): Boolean = withContext(Dispatchers.Main.immediate) { host?.canReopen() == true }
    internal suspend fun reopen(): Boolean = withContext(Dispatchers.Main.immediate) {
        val current = host ?: return@withContext false
        if (!current.canReopen()) return@withContext false
        try { current.reopen() } catch (_: Exception) { false }
    }
    internal suspend fun capture(expiresAtMs: Long,eligible: ()->Boolean): ByteArray? = withContext(Dispatchers.Main.immediate) {
        val current=host?:return@withContext null
        if(!current.canReopen()||!eligible())return@withContext null
        current.captureSupportPanel(expiresAtMs) {host===current&&current.canReopen()&&eligible()}
    }
}
