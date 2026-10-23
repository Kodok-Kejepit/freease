package com.freease.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * endsAt = waktu epoch ms; endOfTrack = berhenti setelah lagu saat ini selesai;
 * songsLeft = berhenti setelah sekian lagu selesai sendiri (lagu yang sedang diputar dihitung).
 */
data class SleepState(val endsAt: Long? = null, val endOfTrack: Boolean = false, val songsLeft: Int? = null) {
    val active get() = endsAt != null || endOfTrack || songsLeft != null

    /** Musik harus berhenti tepat di akhir lagu yang sedang diputar. */
    val stopAtEndOfTrack: Boolean get() = endOfTrack || songsLeft == 1
}

class SleepTimer {
    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state.asStateFlow()

    fun start(minutes: Int, now: Long = System.currentTimeMillis()) {
        _state.value = SleepState(endsAt = now + minutes * 60_000L)
    }

    fun endOfTrack() {
        _state.value = SleepState(endOfTrack = true)
    }

    fun startSongs(count: Int) {
        _state.value = SleepState(songsLeft = count.coerceAtLeast(1))
    }

    /** Tambah waktu pada timer menit yang sedang jalan. @return false kalau bukan timer menit. */
    fun extend(minutes: Int): Boolean {
        val end = _state.value.endsAt ?: return false
        _state.value = SleepState(endsAt = end + minutes * 60_000L)
        return true
    }

    fun cancel() {
        _state.value = SleepState()
    }

    /** Satu lagu selesai sendiri lalu lanjut ke lagu berikutnya: kurangi hitungan "setelah N lagu". */
    fun onTrackFinished() {
        val st = _state.value
        val left = st.songsLeft ?: return
        if (left > 1) _state.value = st.copy(songsLeft = left - 1)
    }

    /** Pemutar sudah berhenti di akhir lagu karena timer ini; timer selesai. */
    fun onStoppedAtEnd() {
        if (_state.value.stopAtEndOfTrack) cancel()
    }
}
