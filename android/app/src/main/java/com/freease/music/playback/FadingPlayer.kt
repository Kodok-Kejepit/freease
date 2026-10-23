package com.freease.music.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Menggabungkan beberapa pengatur volume (fade play/pause, crossfade, sleep timer). */
class VolumeMixer(private val player: Player) {
    var fade = 1f
        set(v) { field = v; apply() }
    var crossfade = 1f
        set(v) { field = v; apply() }
    var sleep = 1f
        set(v) { field = v; apply() }
    var alarm = 1f
        set(v) { field = v; apply() }

    private fun apply() {
        player.volume = (fade * crossfade * sleep * alarm).coerceIn(0f, 1f)
    }
}

/**
 * Player yang dipegang MediaSession. play/pause dari mana pun (notifikasi, headset,
 * layar kunci, UI) diberi fade-in/fade-out singkat supaya tidak ada bunyi "klik".
 */
@UnstableApi
class FadingPlayer(
    private val exo: Player,
    private val mixer: VolumeMixer,
    private val fadeEnabled: () -> Boolean,
) : ForwardingPlayer(exo) {
    private val handler = Handler(exo.applicationLooper)
    private var step: Runnable? = null

    private fun cancelFade() {
        step?.let { handler.removeCallbacks(it) }
        step = null
    }

    /**
     * Fade berbasis waktu lewat Handler, bukan ValueAnimator: animator bergantung pada vsync dan
     * bisa macet saat game/aplikasi lain di depan, membuat volume tertahan di 0 (play tanpa suara).
     */
    private fun animate(to: Float, duration: Long, end: (() -> Unit)? = null) {
        cancelFade()
        val from = mixer.fade
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val f = ((SystemClock.uptimeMillis() - t0).toFloat() / duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - f) * (1f - f)
                mixer.fade = from + (to - from) * eased
                if (f < 1f) {
                    handler.postDelayed(this, 30)
                } else {
                    step = null
                    end?.invoke()
                }
            }
        }
        step = r
        r.run()
    }

    override fun play() {
        if (!fadeEnabled()) {
            cancelFade()
            mixer.fade = 1f
            exo.play()
            return
        }
        if (!exo.isPlaying) mixer.fade = 0f
        exo.play()
        animate(1f, 450)
    }

    override fun pause() {
        if (!fadeEnabled() || !exo.isPlaying) {
            cancelFade()
            exo.pause()
            mixer.fade = 1f
            return
        }
        animate(0f, 260) {
            exo.pause()
            mixer.fade = 1f
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }
}
