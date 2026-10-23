package com.freease.music.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.freease.music.data.prefs.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqInfo(
    val bandsHz: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
)

/**
 * Equalizer, bass boost, virtualizer (3D), dan loudness enhancer yang menempel pada
 * audio session ExoPlayer. Semua efek opsional: perangkat yang tidak mendukung dilewati.
 */
class AudioEffectsController(private val context: Context) {
    private var sessionId = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    private val _info = MutableStateFlow<EqInfo?>(null)
    val info: StateFlow<EqInfo?> = _info.asStateFlow()

    fun attach(audioSessionId: Int, settings: AppSettings) {
        if (audioSessionId == sessionId && eq != null) {
            apply(settings)
            return
        }
        release(broadcastClose = false)
        sessionId = audioSessionId
        if (audioSessionId == 0) return
        eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        virt = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        eq?.let { _info.value = readInfo(it) }
        apply(settings)
        // Supaya aplikasi equalizer sistem (Dolby, Wavelet, dsb.) juga bisa menempel.
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    /** Bangun ulang efek: sistem kadang mereset/mematikan efek saat aplikasi lain (game) memakai audio. */
    fun rebuild(audioSessionId: Int, settings: AppSettings) {
        release(broadcastClose = false)
        sessionId = -1
        attach(audioSessionId, settings)
    }

    fun apply(s: AppSettings) {
        runCatching {
            eq?.let { e ->
                val bands = e.numberOfBands.toInt()
                if (s.eqEnabled) {
                    if (s.eqBands.size == bands) {
                        s.eqBands.forEachIndexed { i, level -> e.setBandLevel(i.toShort(), level.toShort()) }
                    } else if (s.eqPreset in 0 until e.numberOfPresets) {
                        e.usePreset(s.eqPreset.toShort())
                    }
                }
                e.enabled = s.eqEnabled
            }
        }
        runCatching {
            bass?.let { b ->
                if (b.strengthSupported && s.eqEnabled) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
                b.enabled = s.eqEnabled && s.bassBoost > 0
            }
        }
        runCatching {
            virt?.let { v ->
                if (v.strengthSupported && s.eqEnabled) v.setStrength(s.virtualizer.coerceIn(0, 1000).toShort())
                v.enabled = s.eqEnabled && s.virtualizer > 0
            }
        }
        runCatching {
            loud?.let { l ->
                l.setTargetGain(if (s.eqEnabled) s.loudnessGain else 0)
                l.enabled = s.eqEnabled && s.loudnessGain > 0
            }
        }
    }

    /** Info band untuk UI meski player belum jalan (pakai session sementara). */
    fun queryInfo(): EqInfo? {
        _info.value?.let { return it }
        val temp = runCatching {
            Equalizer(0, (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId())
        }.getOrNull() ?: return null
        return try {
            readInfo(temp).also { _info.value = it }
        } finally {
            temp.release()
        }
    }

    fun presetLevels(preset: Int): List<Int> {
        val target = eq
        if (target != null) {
            return runCatching {
                target.usePreset(preset.toShort())
                (0 until target.numberOfBands).map { target.getBandLevel(it.toShort()).toInt() }
            }.getOrDefault(emptyList())
        }
        val temp = runCatching {
            Equalizer(0, (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId())
        }.getOrNull() ?: return emptyList()
        return try {
            temp.usePreset(preset.toShort())
            (0 until temp.numberOfBands).map { temp.getBandLevel(it.toShort()).toInt() }
        } catch (_: Exception) {
            emptyList()
        } finally {
            temp.release()
        }
    }

    private fun readInfo(e: Equalizer): EqInfo {
        val range = e.bandLevelRange
        return EqInfo(
            bandsHz = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 },
            minLevel = range[0].toInt(),
            maxLevel = range[1].toInt(),
            presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
        )
    }

    fun release(broadcastClose: Boolean = true) {
        if (broadcastClose && sessionId != 0) {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName),
            )
        }
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null; loud = null
    }
}
