package com.freease.music.playback.loudness

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Satu filter biquad (Direct Form I) per kanal. */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    companion object {
        fun highShelf(fs: Double, fc: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) - (a - 1) * c + sa
            return Biquad(
                a * ((a + 1) + (a - 1) * c + sa) / a0,
                -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - sa) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - sa) / a0,
            )
        }

        fun lowPass(fs: Double, fc: Double, q: Double): Biquad {
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(fs: Double, fc: Double, q: Double): Biquad {
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * Pengukur loudness terintegrasi ITU-R BS.1770-4 (LUFS): filter K-weighting, blok 400 ms dengan
 * tumpang 75%, gerbang absolut −70 LUFS dan gerbang relatif −10 LU. Bertahap, memori tetap kecil
 * (histogram blok), jadi bisa dipakai langsung saat lagu diputar.
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { Biquad.highShelf(sampleRate.toDouble(), 1681.974450955533, 0.7071752369554196, 3.999843853973347) }
    private val hp = Array(channels) { Biquad.highPass(sampleRate.toDouble(), 38.13547087602444, 0.5003270373238773) }
    private val step = max(1, sampleRate / 10) // 100 ms = seperempat blok
    private val quarters = DoubleArray(4)
    private var quarterCount = 0
    private var acc = 0.0
    private var accN = 0
    private var frame = 0.0
    private var ch = 0

    // Histogram loudness blok −70..+10 LUFS per 0,1 LU, beserta jumlah energinya.
    private val bins = IntArray(BINS)
    private val energy = DoubleArray(BINS)
    var blocks = 0
        private set

    /** Masukkan satu sampel (antar-kanal berselang-seling, rentang −1..1). */
    fun add(sample: Float) {
        val y = hp[ch].process(shelf[ch].process(sample.toDouble()))
        frame += y * y
        ch++
        if (ch < channels) return
        ch = 0
        acc += frame
        frame = 0.0
        accN++
        if (accN < step) return
        quarters[quarterCount % 4] = acc / accN
        quarterCount++
        acc = 0.0
        accN = 0
        if (quarterCount >= 4) addBlock((quarters[0] + quarters[1] + quarters[2] + quarters[3]) / 4)
    }

    private fun addBlock(meanSquare: Double) {
        val lufs = lufs(meanSquare)
        if (lufs < ABS_GATE) return
        val i = ((lufs - ABS_GATE) * 10).toInt().coerceIn(0, BINS - 1)
        bins[i]++
        energy[i] += meanSquare
        blocks++
    }

    /** Loudness terintegrasi sejauh ini, atau null kalau belum cukup data. */
    fun integrated(): Double? {
        if (blocks == 0) return null
        var sum = 0.0
        for (i in 0 until BINS) sum += energy[i]
        val relGate = lufs(sum / blocks) - 10
        var n = 0
        var e = 0.0
        for (i in 0 until BINS) {
            val binLufs = ABS_GATE + i / 10.0
            if (binLufs >= relGate - 0.05) {
                n += bins[i]
                e += energy[i]
            }
        }
        return if (n == 0) null else lufs(e / n)
    }

    /** Detik audio bersuara yang sudah terukur. */
    val measuredSec: Double get() = blocks * 0.1

    fun reset() {
        shelf.forEach { it.reset() }
        hp.forEach { it.reset() }
        bins.fill(0); energy.fill(0.0)
        blocks = 0; quarterCount = 0; acc = 0.0; accN = 0; frame = 0.0; ch = 0
    }

    companion object {
        private const val ABS_GATE = -70.0
        private const val BINS = 800
        fun lufs(meanSquare: Double): Double = -0.691 + 10 * log10(max(meanSquare, 1e-12))
    }
}

/**
 * Menyamakan kekerasan suara antarlagu ke [targetLufs]. Lagu yang loudness-nya sudah diketahui
 * langsung memakai gain tetap; lagu baru disesuaikan perlahan dari perkiraan awal. Limiter
 * mencegah suara pecah saat lagu pelan dikeraskan.
 */
class Normalizer(private val sampleRate: Int, private val channels: Int) {
    @Volatile var targetLufs = -14.0
    @Volatile var enabled = true

    var meter = LoudnessMeter(sampleRate, channels)
        private set

    private var knownLufs: Double? = null
    private var gainDb = 0.0
    private var limiter = 1.0
    private val release = 1.0 / (sampleRate * 0.15) // limiter pulih ±150 ms

    /** Lagu baru mulai: pakai loudness tersimpan kalau ada. */
    fun beginTrack(known: Double?) {
        meter = LoudnessMeter(sampleRate, channels)
        knownLufs = known
        if (known != null) gainDb = desiredGain(known)
    }

    private fun desiredGain(lufs: Double) = (targetLufs - lufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    /** Gain yang sedang diincar (dB), untuk pengujian/diagnosis. */
    fun currentGainDb(): Double = gainDb

    /** Proses [n] sampel berselang-seling di tempat. */
    fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) meter.add(buf[i])
        if (!enabled) {
            gainDb = 0.0
            return
        }
        // Tentukan gain sasaran untuk potongan ini.
        val target = knownLufs?.let(::desiredGain) ?: run {
            val measured = meter.integrated()
            when {
                measured == null || meter.measuredSec < 3 -> desiredGain(TYPICAL_LUFS)
                else -> desiredGain(measured)
            }
        }
        // Gerak gain dibatasi supaya tidak terdengar "memompa" (lagu baru: ±1,5 dB per detik).
        val frames = n / channels
        val maxStep = if (knownLufs != null) 6.0 else 1.5 * frames / sampleRate
        val start = gainDb
        val end = start + (target - start).coerceIn(-maxStep, maxStep)
        gainDb = end
        val g0 = 10.0.pow(start / 20)
        val g1 = 10.0.pow(end / 20)
        var idx = 0
        for (f in 0 until frames) {
            val g = g0 + (g1 - g0) * f / max(1, frames)
            var peak = 0.0
            for (c in 0 until channels) peak = max(peak, kotlin.math.abs(buf[idx + c] * g))
            val need = if (peak > CEILING) CEILING / peak else 1.0
            limiter = if (need < limiter) need else min(1.0, limiter + release)
            val total = (g * limiter).toFloat()
            for (c in 0 until channels) buf[idx + c] *= total
            idx += channels
        }
    }

    companion object {
        /** Loudness umum musik di YouTube, dipakai sebelum lagu baru sempat terukur. */
        const val TYPICAL_LUFS = -9.0
        const val MIN_GAIN_DB = -14.0
        const val MAX_GAIN_DB = 9.0
        private const val CEILING = 0.97
    }
}
