package com.freease.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.freease.music.playback.loudness.Biquad
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Penghilang vokal untuk Mode Karaoke. Vokal hampir selalu di-mix tepat di tengah (sama di kiri &
 * kanan), jadi bagian "tengah" dibuang dan hanya sisi stereo yang tersisa. Bass/kick (< 150 Hz) dan
 * kilau simbal (> 8 kHz) yang juga di tengah dikembalikan supaya musiknya tidak terdengar tipis.
 */
class VocalRemover(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    private val low1 = Biquad.lowPass(fs, 150.0, 0.7071)
    private val low2 = Biquad.lowPass(fs, 150.0, 0.7071)
    private val high1 = Biquad.highPass(fs, 8000.0.coerceAtMost(fs * 0.45), 0.7071)
    private val high2 = Biquad.highPass(fs, 8000.0.coerceAtMost(fs * 0.45), 0.7071)

    /** 0 = suara asli, 1 = vokal dihilangkan penuh; berubah halus agar tidak ada bunyi klik. */
    var target = 0f
    private var mix = 0f
    private val step = (1.0 / (0.3 * fs)).toFloat()

    val idle: Boolean get() = mix == 0f && target == 0f

    /** [buf] berisi sampel stereo berselang-seling (L, R, L, R, …) dalam rentang -1..1. */
    fun process(buf: FloatArray, count: Int) {
        var i = 0
        while (i + 1 < count) {
            val l = buf[i]
            val r = buf[i + 1]
            val mid = (l + r) * 0.5
            val low = low2.process(low1.process(mid))
            val high = high2.process(high1.process(mid))
            if (mix != target) mix = if (mix < target) minOf(target, mix + step) else maxOf(target, mix - step)
            if (mix > 0f) {
                val side = (l - r) * 0.5 * SIDE_GAIN
                val keep = low + high * 0.7
                val kl = (side + keep).toFloat()
                val kr = (-side + keep).toFloat()
                buf[i] = l + (kl - l) * mix
                buf[i + 1] = r + (kr - r) * mix
            }
            i += 2
        }
    }

    private companion object {
        /** Sisi stereo biasanya lebih pelan dari tengah; sedikit dikuatkan. */
        const val SIDE_GAIN = 1.6
    }
}

@UnstableApi
class KaraokeAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
    private var remover: VocalRemover? = null
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
        ) {
            remover = null
            return AudioFormat.NOT_SET
        }
        remover = VocalRemover(inputAudioFormat.sampleRate).also { it.target = if (enabled) 1f else 0f }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val r = remover ?: return
        r.target = if (enabled) 1f else 0f
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        if (r.idle) {
            // Tidak aktif: salin apa adanya (murah).
            out.put(inputBuffer)
            out.flip()
            return
        }
        val count = if (float) bytes / 4 else bytes / 2
        if (floats.size < count) floats = FloatArray(count)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) {
            src.asFloatBuffer().get(floats, 0, count)
        } else {
            val sb = src.asShortBuffer()
            for (i in 0 until count) floats[i] = sb.get(i) / 32768f
        }
        inputBuffer.position(inputBuffer.position() + bytes)
        r.process(floats, count)
        if (float) {
            for (i in 0 until count) out.putFloat(floats[i])
        } else {
            for (i in 0 until count) out.putShort((floats[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        out.flip()
    }

    override fun onReset() {
        remover = null
    }
}
