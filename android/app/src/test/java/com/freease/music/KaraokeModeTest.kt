package com.freease.music

import com.freease.music.playback.SongParams
import com.freease.music.playback.VocalRemover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class KaraokeModeTest {
    private val fs = 44100

    private fun process(input: FloatArray, on: Boolean): FloatArray {
        val r = VocalRemover(fs).apply { target = if (on) 1f else 0f }
        val frames = input.size / 2
        val out = FloatArray(input.size + 8192 * 2)
        var o = 0
        var i = 0
        val sizes = intArrayOf(512, 1024, 4096, 333, 2048)
        var c = 0
        val tmp = FloatArray((4096 + 4096) * 2)
        while (i < frames) {
            val n = minOf(sizes[c++ % sizes.size], frames - i)
            val w = r.process(input.copyOfRange(i * 2, (i + n) * 2), n, tmp)
            System.arraycopy(tmp, 0, out, o * 2, w * 2)
            o += w
            i += n
        }
        val t = r.flush(tmp)
        System.arraycopy(tmp, 0, out, o * 2, t * 2)
        o += t
        assertEquals("jumlah sampel keluar = masuk", frames, o)
        return out.copyOf(frames * 2)
    }

    /** Amplitudo nada [f] di kanal [ch] (detik 1–3, setelah efek stabil). */
    private fun tone(buf: FloatArray, ch: Int, f: Double): Double {
        var re = 0.0; var im = 0.0; var n = 0
        for (i in fs until 3 * fs) {
            val x = buf[2 * i + ch]
            re += x * cos(2 * PI * f * i / fs); im += x * sin(2 * PI * f * i / fs); n++
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    private fun rms(buf: FloatArray): Double {
        var s = 0.0
        for (i in 2 * fs until buf.size) s += buf[i] * buf[i].toDouble()
        return sqrt(s / (buf.size - 2 * fs))
    }

    private fun mix(seconds: Int): FloatArray {
        val n = fs * seconds
        val b = FloatArray(n * 2)
        for (i in 0 until n) {
            val vocal = 0.30 * sin(2 * PI * 1000.0 * i / fs)  // tengah, rentang vokal
            val bass = 0.25 * sin(2 * PI * 60.0 * i / fs)     // tengah, bass
            val guitar = 0.20 * sin(2 * PI * 700.0 * i / fs)  // kiri saja
            val keys = 0.12 * sin(2 * PI * 1500.0 * i / fs)   // kanan, sebagian ikut di tengah
            b[2 * i] = (vocal + bass + guitar + keys * 0.3).toFloat()
            b[2 * i + 1] = (vocal + bass + keys).toFloat()
        }
        return b
    }

    @Test
    fun offIsTransparent() {
        val input = mix(3)
        val out = process(input, on = false)
        var maxErr = 0f
        for (i in input.indices) maxErr = maxOf(maxErr, kotlin.math.abs(out[i] - input[i]))
        assertTrue("selisih $maxErr", maxErr < 1e-4f)
    }

    @Test
    fun removesCenteredVocalOnly() {
        val input = mix(4)
        val out = process(input, on = true)
        val vocalBefore = tone(input, 1, 1000.0)
        val vocalAfter = tone(out, 1, 1000.0)
        val bassAfter = tone(out, 0, 60.0)
        val guitarAfter = tone(out, 0, 700.0)
        val keysAfter = tone(out, 1, 1500.0)
        println("vokal ${"%.3f".format(vocalBefore)} -> ${"%.4f".format(vocalAfter)}, bass ${"%.3f".format(bassAfter)}, gitar ${"%.3f".format(guitarAfter)}, keys ${"%.3f".format(keysAfter)}")
        println("rms ${"%.3f".format(rms(input))} -> ${"%.3f".format(rms(out))}")
        assertTrue("vokal masih ${vocalAfter}", vocalAfter < vocalBefore * 0.08) // > 22 dB lebih pelan
        assertTrue("bass ${bassAfter}", bassAfter > 0.22)
        assertTrue("gitar ${guitarAfter}", guitarAfter > 0.18)
        // Bagian "tengah"-nya ikut berkurang, tapi tetap terdengar jelas.
        assertTrue("keys ${keysAfter}", keysAfter > 0.05)
        // Keseluruhan tidak ikut mengecil.
        assertTrue("rms", rms(out) > rms(input) * 0.75)
    }

    /** Lagu stereo sungguhan (opsional): -Pkaraoke.pcm=berkas s16le 44,1 kHz stereo. */
    @Test
    fun realSong() {
        val path = System.getProperty("karaoke.pcm")
        Assume.assumeTrue(path != null && File(path).exists())
        val bytes = File(path!!).readBytes().let { it.copyOf(minOf(it.size, fs * 4 * 60)) }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val input = FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        val out = process(input, on = true)
        fun bandEnergy(b: FloatArray, mid: Boolean): Double {
            var s = 0.0
            for (i in 2 * fs until b.size / 2) {
                val m = if (mid) (b[2 * i] + b[2 * i + 1]) / 2 else (b[2 * i] - b[2 * i + 1]) / 2
                s += m * m.toDouble()
            }
            return s
        }
        val db = { a: Double, b: Double -> 10 * kotlin.math.log10(a / b) }
        println("REAL rms ${"%.3f".format(rms(input))} -> ${"%.3f".format(rms(out))} (${"%.1f".format(20 * kotlin.math.log10(rms(out) / rms(input)))} dB)")
        println("REAL tengah ${"%.1f".format(db(bandEnergy(out, true), bandEnergy(input, true)))} dB, samping ${"%.1f".format(db(bandEnergy(out, false), bandEnergy(input, false)))} dB")
    }

    /**
     * Uji realistis (opsional): -Pkaraoke.mix=DIR berisi inst.pcm (lagu stereo s16le 44,1 kHz) dan
     * voc.pcm (suara mono). Suara ditaruh tepat di tengah seperti vokal utama, lalu diukur
     * seberapa banyak suara yang tersisa dan seberapa utuh musiknya.
     */
    @Test
    fun realMix() {
        val dir = System.getProperty("karaoke.mix")
        Assume.assumeTrue(dir != null && File(dir, "inst.pcm").exists())
        fun read(f: File): FloatArray {
            val sb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
        }
        val inst = read(File(dir, "inst.pcm"))
        val voc = read(File(dir, "voc.pcm"))
        val frames = minOf(inst.size / 2, voc.size)
        val input = FloatArray(frames * 2) { i -> inst[i] + voc[i / 2] }
        val out = process(input, on = true)
        // Koefisien suara di keluaran (proyeksi kuadrat-terkecil pada kanal tengah).
        var dot = 0.0; var vv = 0.0; var dotIn = 0.0
        for (i in 2 * fs until frames) {
            val m = (out[2 * i] + out[2 * i + 1]) / 2.0
            val mi = (input[2 * i] + input[2 * i + 1]) / 2.0
            dot += m * voc[i]; dotIn += mi * voc[i]; vv += voc[i] * voc[i].toDouble()
        }
        val before = dotIn / vv
        val after = dot / vv
        val instRms = rms(inst.copyOf(frames * 2))
        val outRms = rms(out)
        val inRms = rms(input)
        println("MIX suara ${"%.2f".format(before)} -> ${"%.3f".format(after)} (${"%.1f".format(20 * kotlin.math.log10(kotlin.math.abs(after) / before))} dB)")
        println("MIX rms campuran ${"%.3f".format(inRms)}, musik saja ${"%.3f".format(instRms)}, hasil ${"%.3f".format(outRms)} (${"%.1f".format(20 * kotlin.math.log10(outRms / inRms))} dB dari campuran)")
        assertTrue("suara masih ${after}", kotlin.math.abs(after) < before * 0.25) // > 12 dB lebih pelan
        assertTrue("hasil terlalu pelan", outRms > inRms * 0.7)
    }

    @Test
    fun semitones() {
        assertEquals(0, SongParams.semitones(1f))
        assertEquals(2, SongParams.semitones(SongParams.pitchOf(2)))
        assertEquals(-3, SongParams.semitones(SongParams.pitchOf(-3)))
        assertEquals(2f, SongParams.pitchOf(12), 0.001f)
    }
}
