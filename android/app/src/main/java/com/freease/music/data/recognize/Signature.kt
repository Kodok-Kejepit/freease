package com.freease.music.data.recognize

import com.freease.music.data.lyrics.align.Fft
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Sidik jari audio format Shazam (16 kHz mono). Puncak-puncak spektrum dicari per pita
 * frekuensi lalu dikodekan ke format biner yang dimengerti server pengenal lagu.
 * Algoritma mengikuti implementasi terbuka SongRec.
 */
class SignatureGenerator {
    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    private val samples = ShortArray(2048)
    private var samplesPos = 0
    private val fftOut = Array(256) { FloatArray(1025) }
    private var fftPos = 0
    private val spread = Array(256) { FloatArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0
    private val bands = sortedMapOf<Int, MutableList<Peak>>()
    var numberSamples = 0
        private set

    private val fft = Fft(2048)
    private val re = FloatArray(2048)
    private val im = FloatArray(2048)

    /** Tambah sampel PCM 16-bit mono 16 kHz (panjang kelipatan 128 paling ideal). */
    fun feed(pcm: ShortArray, length: Int = pcm.size) {
        var i = 0
        while (i + 128 <= length) {
            processChunk(pcm, i)
            i += 128
        }
        numberSamples += i
    }

    private fun processChunk(pcm: ShortArray, offset: Int) {
        System.arraycopy(pcm, offset, samples, samplesPos, 128)
        samplesPos = (samplesPos + 128) % 2048
        for (k in 0 until 2048) {
            re[k] = samples[(samplesPos + k) % 2048] * HANNING[k]
            im[k] = 0f
        }
        fft.transform(re, im)
        val out = fftOut[fftPos]
        for (k in 0 until 1025) {
            out[k] = max((re[k] * re[k] + im[k] * im[k]) / (1 shl 17), 1e-10f)
        }
        fftPos = (fftPos + 1) % 256
        spreadPeaks()
        if (spreadWritten >= 46) recognizePeaks()
    }

    private fun spreadPeaks() {
        val origin = fftOut[(fftPos - 1 + 256) % 256]
        val s = spread[spreadPos]
        System.arraycopy(origin, 0, s, 0, 1025)
        for (p in 0 until 1025) {
            if (p < 1023) s[p] = max(s[p], max(s[p + 1], s[p + 2]))
            var mx = s[p]
            for (former in FORMER) {
                val f = spread[(spreadPos + former + 256) % 256]
                mx = max(f[p], mx)
                f[p] = mx
            }
        }
        spreadPos = (spreadPos + 1) % 256
        spreadWritten++
    }

    private fun recognizePeaks() {
        val m46 = fftOut[(fftPos - 46 + 256) % 256]
        val m49 = spread[(spreadPos - 49 + 256) % 256]
        for (bin in 10 until 1015) {
            val v = m46[bin]
            if (v < 1f / 64 || v < m49[bin - 1]) continue
            var maxNeighbor = 0f
            for (o in NEIGHBORS) maxNeighbor = max(m49[bin + o], maxNeighbor)
            if (v <= maxNeighbor) continue
            var maxOther = maxNeighbor
            for (o in OTHER) maxOther = max(spread[((spreadPos + o) % 256 + 256) % 256][bin - 1], maxOther)
            if (v <= maxOther) continue

            val pass = spreadWritten - 46
            fun mag(x: Float) = ln(max(1f / 64, x).toDouble()) * 1477.3 + 6144
            val peak = mag(v)
            val before = mag(m46[bin - 1])
            val after = mag(m46[bin + 1])
            val var1 = peak * 2 - before - after
            if (var1 <= 0) continue
            val var2 = (after - before) * 32 / var1
            val corrected = bin * 64 + var2
            val hz = corrected * (16000.0 / 2 / 1024 / 64)
            val band = when {
                hz < 250 -> continue
                hz < 520 -> 0
                hz < 1450 -> 1
                hz < 3500 -> 2
                hz <= 5500 -> 3
                else -> continue
            }
            bands.getOrPut(band) { ArrayList() } += Peak(pass, peak.toInt(), corrected.toInt())
        }
    }

    val peakCount: Int get() = bands.values.sumOf { it.size }

    /** Salinan puncak yang sudah terkumpul — supaya bisa dikirim sambil terus merekam. */
    fun snapshot(): SignatureGenerator {
        val copy = SignatureGenerator()
        bands.forEach { (k, v) -> copy.bands[k] = ArrayList(v) }
        copy.numberSamples = numberSamples
        return copy
    }

    /** Sidik jari biner lengkap dengan header & CRC. */
    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands) {
            val buf = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.pass - pass >= 255) {
                    buf.write(0xff)
                    buf.write(le32(p.pass))
                    pass = p.pass
                }
                buf.write(p.pass - pass)
                buf.write(le16(p.magnitude))
                buf.write(le16(p.bin))
                pass = p.pass
            }
            val bytes = buf.toByteArray()
            contents.write(le32(0x60030040 + band))
            contents.write(le32(bytes.size))
            contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val out = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xcafe2580.toInt())
        out.putInt(0) // crc32, diisi di bawah
        out.putInt(body.size + 8)
        out.putInt(0x94119c00.toInt())
        repeat(3) { out.putInt(0) }
        out.putInt(3 shl 27) // 16000 Hz
        repeat(2) { out.putInt(0) }
        out.putInt((numberSamples + 16000 * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(body.size + 8)
        out.put(body)
        val arr = out.array()
        val crc = CRC32().apply { update(arr, 8, arr.size - 8) }.value
        ByteBuffer.wrap(arr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        return arr
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    companion object {
        const val SAMPLE_RATE = 16_000
        private val FORMER = intArrayOf(-1, -3, -6)
        private val NEIGHBORS = intArrayOf(-10, -7, -3, 1, 2, 5, 8)
        private val OTHER = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        /** numpy.hanning(2050)[1:-1] */
        private val HANNING = FloatArray(2048) { (0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049)).toFloat() }
    }
}
