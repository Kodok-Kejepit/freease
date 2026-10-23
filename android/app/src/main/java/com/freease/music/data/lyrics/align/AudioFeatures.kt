package com.freease.music.data.lyrics.align

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sidik audio ringkas: energi log per pita frekuensi, [bands] nilai per frame, satu frame tiap [frameMs].
 * Cukup untuk mencocokkan dua salinan rekaman yang sama (versi lagu vs video klip), dan tahan terhadap
 * beda codec, bitrate, volume, maupun EQ.
 */
class AudioFeatures(val frameMs: Double, val bands: Int, val data: FloatArray) {
    val frames: Int get() = data.size / bands
    val durationMs: Long get() = (frames * frameMs).toLong()
}

/**
 * Mengubah PCM (berapa pun sample rate & jumlah kanalnya) menjadi [AudioFeatures] secara bertahap,
 * jadi lagu 5 menit tidak perlu disimpan utuh di memori. Audio diturunkan dulu ke ±12 kHz mono
 * supaya FFT kecil dan cepat di HP.
 */
class AudioFeatureExtractor(inputRate: Int, private val channels: Int) {
    private val decim = max(1, inputRate / TARGET_RATE)
    private val rate = inputRate.toDouble() / decim
    private val hop = (rate / FPS).roundToInt()
    private val n = Integer.highestOneBit(hop * 2 - 1) shl 1
    val frameMs: Double = hop * 1000.0 / rate

    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val fft = Fft(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    /** Rentang bin FFT tiap pita, berjarak logaritmis 100 Hz – 5 kHz. */
    private val bandLo: IntArray
    private val bandHi: IntArray

    init {
        val top = min(MAX_HZ, rate / 2 * 0.95)
        val edges = DoubleArray(BANDS + 1) { MIN_HZ * (top / MIN_HZ).pow(it.toDouble() / BANDS) }
        val binHz = rate / n
        bandLo = IntArray(BANDS) { max(1, (edges[it] / binHz).toInt()) }
        bandHi = IntArray(BANDS) { max(bandLo[it] + 1, (edges[it + 1] / binHz).toInt()) }
    }

    private val ring = FloatArray(n)
    private var ringPos = 0
    private var filled = 0
    private var sinceHop = 0
    private var acc = 0f
    private var accCount = 0
    private var channel = 0
    private var frameSum = 0f

    private var out = FloatArray(BANDS * 4096)
    private var outSize = 0

    fun feed(pcm: ShortArray, count: Int) {
        for (i in 0 until count) push(pcm[i] / 32768f)
    }

    fun feed(pcm: FloatArray, count: Int) {
        for (i in 0 until count) push(pcm[i])
    }

    private fun push(sample: Float) {
        // Rata-rata kanal → mono, lalu rata-rata [decim] sampel → sample rate lebih rendah.
        frameSum += sample
        channel++
        if (channel < channels) return
        acc += frameSum / channels
        frameSum = 0f
        channel = 0
        accCount++
        if (accCount < decim) return
        ring[ringPos] = acc / decim
        acc = 0f
        accCount = 0
        ringPos = (ringPos + 1) % n
        if (filled < n) filled++
        sinceHop++
        if (filled == n && sinceHop >= hop) {
            sinceHop = 0
            analyze()
        }
    }

    private fun analyze() {
        for (i in 0 until n) {
            re[i] = ring[(ringPos + i) % n] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        if (outSize + BANDS > out.size) out = out.copyOf(out.size * 2)
        for (b in 0 until BANDS) {
            var e = 0.0
            for (k in bandLo[b] until bandHi[b]) e += (re[k] * re[k] + im[k] * im[k]).toDouble()
            out[outSize + b] = ln(1e-7 + e / (bandHi[b] - bandLo[b])).toFloat()
        }
        outSize += BANDS
    }

    fun finish(): AudioFeatures = AudioFeatures(frameMs, BANDS, out.copyOf(outSize))

    companion object {
        const val TARGET_RATE = 12_000
        const val FPS = 50.0
        const val BANDS = 16
        const val MIN_HZ = 100.0
        const val MAX_HZ = 5_000.0
    }
}

/** FFT radix-2 kompleks di tempat (ukuran pangkat dua). */
class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n >= 2 && n and (n - 1) == 0) { "Ukuran FFT harus pangkat dua" }
    }

    /** [inverse] = true menghitung FFT terbalik tanpa pembagian 1/n. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) -1f else 1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]
                    val s = sign * sinT[k]
                    val l = j + half
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
