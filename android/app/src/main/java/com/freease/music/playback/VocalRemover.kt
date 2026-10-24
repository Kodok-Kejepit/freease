package com.freease.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Penghilang vokal untuk Mode Karaoke, bekerja per frekuensi (STFT).
 *
 * Vokal utama hampir selalu di-mix tepat di tengah: di kiri & kanan sama persis. Di tiap potongan
 * frekuensi, bagian "tengah" diperkirakan (daya tengah dikurangi daya samping) lalu dibuang — hanya
 * di rentang suara vokal (±90 Hz – 11 kHz). Bass & kick di bawahnya, simbal di atasnya, dan instrumen
 * yang condong ke kiri/kanan tetap utuh, lalu volume keseluruhan dijaga supaya musiknya tidak ikut mengecil.
 *
 * Keluaran tertahan [latency] sampel (±23 ms) di dalam; tidak ada sampel yang hilang atau disisipkan:
 * jumlah keluaran = jumlah masukan setelah [flush], jadi menyala/mati di tengah lagu tanpa celah.
 */
class VocalRemover(private val sampleRate: Int) {
    private val n = if (sampleRate > 24_000) 2048 else 1024
    private val hop = n / 2
    val latency: Int get() = hop

    private val fft = com.freease.music.data.lyrics.align.Fft(n)
    /** Jendela akar-Hann untuk analisis & sintesis: tumpang-tindih 50% menghasilkan rekonstruksi sempurna. */
    private val win = FloatArray(n) { kotlin.math.sqrt(0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / n)).toFloat() }
    private val bandGain = FloatArray(n / 2 + 1) { k ->
        val hz = k.toDouble() * sampleRate / n
        when {
            hz < 90 -> 0f
            hz < 150 -> ((hz - 90) / 60).toFloat()
            hz < 8_000 -> 1f
            hz < 11_000 -> ((11_000 - hz) / 3_000).toFloat()
            else -> 0f
        }
    }

    private val inL = FloatArray(n)
    private val inR = FloatArray(n)
    private var fill = hop // separuh awal = nol (riwayat)
    private val olaL = FloatArray(n)
    private val olaR = FloatArray(n)
    private val reL = FloatArray(n); private val imL = FloatArray(n)
    private val reR = FloatArray(n); private val imR = FloatArray(n)
    private val pm = FloatArray(n / 2 + 1)
    private val ps = FloatArray(n / 2 + 1)
    private val pending = ArrayDeque<FloatArray>() // blok keluaran [L,R,L,R…] siap kirim

    /** 0 = suara asli, 1 = vokal dihilangkan penuh; berubah halus agar tidak ada bunyi klik. */
    var target = 0f
    private var strength = 0f
    private val strengthStep = hop.toFloat() / (0.3f * sampleRate)

    // Penjaga volume: energi masuk vs keluar (rata-rata ±2 detik).
    private var eIn = 1e-6
    private var eOut = 1e-6
    private var gain = 1f
    private val energyA = kotlin.math.exp(-hop / (2.0 * sampleRate))

    private var fedFrames = 0L
    private var outFrames = 0L
    /** Blok pertama hanya berisi "riwayat" nol — dibuang supaya keluaran menyambung tanpa jeda. */
    private var skip = hop

    val idle: Boolean get() = strength == 0f && target == 0f

    /**
     * Masukkan [frames] sampel stereo berselang-seling dari [src]; keluaran (tertunda [latency])
     * ditulis ke [dst] dan jumlah frame yang ditulis dikembalikan.
     */
    fun process(src: FloatArray, frames: Int, dst: FloatArray): Int {
        var written = 0
        var i = 0
        while (i < frames) {
            inL[fill] = src[2 * i]
            inR[fill] = src[2 * i + 1]
            fill++
            i++
            if (fill == n) {
                frame()
                System.arraycopy(inL, hop, inL, 0, hop)
                System.arraycopy(inR, hop, inR, 0, hop)
                fill = hop
            }
        }
        fedFrames += frames
        while (pending.isNotEmpty()) {
            val b = pending.removeFirst()
            val frames0 = b.size / 2
            val drop = minOf(skip, frames0)
            skip -= drop
            val keep = frames0 - drop
            if (keep > 0) {
                System.arraycopy(b, drop * 2, dst, written * 2, keep * 2)
                written += keep
            }
        }
        outFrames += written
        return written
    }

    /** Sisa sampel yang masih tertahan (dipanggil di akhir lagu / sebelum kembali ke mode biasa). */
    fun flush(dst: FloatArray): Int {
        val remaining = (fedFrames - outFrames).toInt()
        if (remaining <= 0) return 0
        val zeros = FloatArray(2 * n)
        val tmp = FloatArray(4 * n)
        val got = process(zeros, n, tmp)
        val take = minOf(remaining, got)
        System.arraycopy(tmp, 0, dst, 0, take * 2)
        reset()
        return take
    }

    fun reset() {
        inL.fill(0f); inR.fill(0f); olaL.fill(0f); olaR.fill(0f); pm.fill(0f); ps.fill(0f)
        fill = hop
        skip = hop
        pending.clear()
        fedFrames = 0
        outFrames = 0
    }

    private fun frame() {
        if (strength != target) {
            strength = if (strength < target) minOf(target, strength + strengthStep) else maxOf(target, strength - strengthStep)
        }
        var energyIn = 0.0
        for (k in 0 until n) {
            reL[k] = inL[k] * win[k]; imL[k] = 0f
            reR[k] = inR[k] * win[k]; imR[k] = 0f
            if (k >= hop) energyIn += inL[k] * inL[k] + inR[k] * inR[k]
        }
        if (strength > 0f) {
            fft.transform(reL, imL)
            fft.transform(reR, imR)
            for (k in 0..n / 2) {
                val band = bandGain[k]
                if (band > 0f) {
                    // Tengah (M) & samping (S). Musik stereo mengisi keduanya; vokal tengah hanya M.
                    // Perkiraan daya vokal = daya M − daya S (dihaluskan antar-frame).
                    val mRe = (reL[k] + reR[k]) * 0.5f
                    val mIm = (imL[k] + imR[k]) * 0.5f
                    val sRe = (reL[k] - reR[k]) * 0.5f
                    val sIm = (imL[k] - imR[k]) * 0.5f
                    pm[k] = SMOOTH * pm[k] + (1 - SMOOTH) * (mRe * mRe + mIm * mIm)
                    ps[k] = SMOOTH * ps[k] + (1 - SMOOTH) * (sRe * sRe + sIm * sIm)
                    val g = if (pm[k] > 1e-12f) kotlin.math.sqrt((1f - OVER * ps[k] / pm[k]).coerceAtLeast(0f)) else 0f
                    val c = g * band * strength
                    if (c > 0f) {
                        reL[k] -= mRe * c; imL[k] -= mIm * c
                        reR[k] -= mRe * c; imR[k] -= mIm * c
                    }
                }
                if (k in 1 until n / 2) {
                    reL[n - k] = reL[k]; imL[n - k] = -imL[k]
                    reR[n - k] = reR[k]; imR[n - k] = -imR[k]
                }
            }
            fft.transform(reL, imL, inverse = true)
            fft.transform(reR, imR, inverse = true)
            val inv = 1f / n
            for (k in 0 until n) {
                reL[k] *= inv * win[k]
                reR[k] *= inv * win[k]
            }
        } else {
            for (k in 0 until n) {
                reL[k] *= win[k]
                reR[k] *= win[k]
            }
        }
        for (k in 0 until n) {
            olaL[k] += reL[k]
            olaR[k] += reR[k]
        }
        // Blok siap: separuh pertama OLA.
        val out = FloatArray(hop * 2)
        var energyOut = 0.0
        for (k in 0 until hop) {
            energyOut += olaL[k] * olaL[k] + olaR[k] * olaR[k]
        }
        eIn = energyA * eIn + (1 - energyA) * energyIn
        eOut = energyA * eOut + (1 - energyA) * energyOut
        // Kembalikan volume yang hilang bersama vokal (maks +9 dB), hanya sebanding dengan kekuatan efek.
        val want = if (strength > 0f) {
            (kotlin.math.sqrt(eIn / eOut).toFloat() * 0.92f).coerceIn(1f, 2.8f).let { 1f + (it - 1f) * strength }
        } else 1f
        for (k in 0 until hop) {
            gain += (want - gain) * 0.002f
            out[2 * k] = softClip(olaL[k] * gain)
            out[2 * k + 1] = softClip(olaR[k] * gain)
        }
        System.arraycopy(olaL, hop, olaL, 0, hop)
        System.arraycopy(olaR, hop, olaR, 0, hop)
        java.util.Arrays.fill(olaL, hop, n, 0f)
        java.util.Arrays.fill(olaR, hop, n, 0f)
        pending.addLast(out)
    }

    private companion object {
        /** Penghalusan daya antar-frame (±50 ms). */
        const val SMOOTH = 0.5f
        /** Bobot pengurangan samping: >1 lebih hati-hati terhadap instrumen yang agak ke tengah. */
        const val OVER = 1.0f
    }

    /** Pembatas lembut di atas 0,9 supaya penguatan tidak pernah pecah. */
    private fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * kotlin.math.tanh(((a - 0.9f) / 0.1f).toDouble()).toFloat()
        return if (x < 0) -y else y
    }
}

@UnstableApi
class KaraokeAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
    private var remover: VocalRemover? = null
    private var floats = FloatArray(0)
    private var outFloats = FloatArray(0)
    /** Sedang lewat jalur STFT (bukan salin langsung). */
    private var engaged = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        remover = VocalRemover(inputAudioFormat.sampleRate).also { it.target = if (enabled) 1f else 0f }
        engaged = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val r = remover ?: return
        r.target = if (enabled) 1f else 0f
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!engaged && r.idle) {
            // Tidak aktif: salin apa adanya (murah, tanpa jeda).
            val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
            out.put(inputBuffer)
            out.flip()
            return
        }
        engaged = true
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
        val frames = count / 2
        val need = (frames + 4096) * 2
        if (outFloats.size < need) outFloats = FloatArray(need)
        var produced = r.process(floats, frames, outFloats)
        if (r.idle) {
            // Efek sudah pudar habis: keluarkan sisa yang tertahan lalu kembali ke mode salin.
            val tail = FloatArray(4096 * 2)
            val t = r.flush(tail)
            System.arraycopy(tail, 0, outFloats, produced * 2, t * 2)
            produced += t
            engaged = false
        }
        write(outFloats, produced * 2, float)
    }

    override fun onQueueEndOfStream() {
        val r = remover ?: return
        if (!engaged) return
        val tail = FloatArray(4096 * 2)
        val t = r.flush(tail)
        if (t > 0) write(tail, t * 2, inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
    }

    private fun write(data: FloatArray, count: Int, float: Boolean) {
        val out = replaceOutputBuffer(count * if (float) 4 else 2).order(ByteOrder.nativeOrder())
        if (float) {
            for (i in 0 until count) out.putFloat(data[i])
        } else {
            for (i in 0 until count) out.putShort((data[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        out.flip()
    }

    override fun onReset() {
        remover = null
        engaged = false
    }
}
