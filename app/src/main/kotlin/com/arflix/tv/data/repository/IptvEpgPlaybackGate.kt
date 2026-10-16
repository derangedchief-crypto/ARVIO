package com.arflix.tv.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps background guide work from competing with live video playback. */
@Singleton
class IptvEpgPlaybackGate @Inject constructor() {
    private val _playbackActive = MutableStateFlow(false)
    val playbackActive: StateFlow<Boolean> = _playbackActive

    fun setPlaybackActive(active: Boolean) {
        _playbackActive.value = active
    }

    fun shouldYieldToPlayback(): Boolean = _playbackActive.value

    suspend fun awaitPlaybackIdle() {
        playbackActive.filter { active -> !active }.first()
    }
}
