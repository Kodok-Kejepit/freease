package com.freease.music.playback

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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

    private fun apply() {
        player.volume = (fade * crossfade * sleep).coerceIn(0f, 1f)
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
    private var animator: ValueAnimator? = null

    private fun animate(to: Float, duration: Long, end: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(mixer.fade, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { mixer.fade = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    override fun play() {
        if (!fadeEnabled()) {
            animator?.cancel()
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
            animator?.cancel()
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
