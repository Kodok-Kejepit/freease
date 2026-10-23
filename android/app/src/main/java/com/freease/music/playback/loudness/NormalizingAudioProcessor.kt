package com.freease.music.playback.loudness

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Pemroses audio ExoPlayer untuk normalisasi volume. Berjalan di thread pemutaran; permintaan dari
 * thread utama (lagu berganti, pengaturan berubah) dititipkan lalu diterapkan pada potongan berikutnya.
 */
@UnstableApi
class NormalizingAudioProcessor(
    /** Dipanggil saat sebuah lagu selesai diukur: (id lagu, LUFS, detik terukur). */
    private val onMeasured: (String, Double, Double) -> Unit,
) : BaseAudioProcessor() {
    @Volatile var enabled = true
    @Volatile var targetLufs = -14.0

    private class TrackStart(val id: String?, val known: Double?)

    private val pending = AtomicReference<TrackStart?>(null)
    private var normalizer: Normalizer? = null
    private var trackId: String? = null
    private var known: Double? = null
    private var floats = FloatArray(0)

    /** Lagu [id] mulai diputar; [knownLufs] = loudness tersimpan dari putaran sebelumnya. */
    fun beginTrack(id: String?, knownLufs: Double?) {
        pending.set(TrackStart(id, knownLufs))
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        normalizer = Normalizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount).also { it.beginTrack(known) }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n0 = normalizer ?: return
        pending.getAndSet(null)?.let { start ->
            finishTrack(n0)
            trackId = start.id
            known = start.known
            n0.beginTrack(start.known)
        }
        n0.enabled = enabled
        n0.targetLufs = targetLufs

        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytes = inputBuffer.remaining()
        val count = if (float) bytes / 4 else bytes / 2
        if (count == 0) return
        if (floats.size < count) floats = FloatArray(count)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) {
            src.asFloatBuffer().get(floats, 0, count)
        } else {
            val sb = src.asShortBuffer()
            for (i in 0 until count) floats[i] = sb.get(i) / 32768f
        }
        inputBuffer.position(inputBuffer.position() + bytes)

        n0.process(floats, count)

        val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        if (float) {
            for (i in 0 until count) out.putFloat(floats[i])
        } else {
            for (i in 0 until count) out.putShort((floats[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        out.flip()
    }

    private fun finishTrack(n: Normalizer) {
        val id = trackId ?: return
        val lufs = n.meter.integrated() ?: return
        onMeasured(id, lufs, n.meter.measuredSec)
    }

    override fun onReset() {
        normalizer = null
    }
}
