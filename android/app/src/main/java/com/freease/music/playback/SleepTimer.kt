package com.freease.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** endsAt = waktu epoch ms; endOfTrack = berhenti setelah lagu saat ini selesai. */
data class SleepState(val endsAt: Long? = null, val endOfTrack: Boolean = false) {
    val active get() = endsAt != null || endOfTrack
}

class SleepTimer {
    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()

    fun start(minutes: Int) {
        _state.value = SleepState(endsAt = System.currentTimeMillis() + minutes * 60_000L)
    }

    fun endOfTrack() {
        _state.value = SleepState(endOfTrack = true)
    }

    fun cancel() {
        _state.value = SleepState()
    }
}
