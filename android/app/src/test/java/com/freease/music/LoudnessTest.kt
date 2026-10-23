package com.freease.music

import com.freease.music.playback.loudness.LoudnessMeter
import com.freease.music.playback.loudness.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessTest {
    private val rate = 48_000

    /** Sinus stereo [amp] di [hz] selama [sec] detik. */
    private fun sine(amp: Double, hz: Double, sec: Int) = FloatArray(rate * sec * 2) { i ->
        (amp * sin(2 * PI * hz * (i / 2) / rate)).toFloat()
    }

    @Test fun measuresReferenceToneLikeBs1770() {
        // Sinus 1 kHz −20 dBFS di kedua kanal = −20 LUFS (acuan kalibrasi BS.1770).
        val m = LoudnessMeter(rate, 2)
        sine(0.1, 1000.0, 10).forEach(m::add)
        assertEquals(-20.0, m.integrated()!!, 0.3)
    }

    @Test fun gatesOutSilence() {
        // Lagu dengan jeda sunyi panjang: hening tidak boleh membuat lagunya terukur lebih pelan.
        val m = LoudnessMeter(rate, 2)
        sine(0.1, 1000.0, 5).forEach(m::add)
        FloatArray(rate * 10 * 2).forEach(m::add)
        sine(0.1, 1000.0, 5).forEach(m::add)
        assertEquals(-20.0, m.integrated()!!, 0.4)
    }

    private fun rms(buf: FloatArray, from: Int = 0) = sqrt(buf.drop(from).sumOf { it.toDouble() * it } / (buf.size - from))

    @Test fun loudSongIsTurnedDownGradually() {
        val n = Normalizer(rate, 2).apply { targetLufs = -14.0; beginTrack(null) }
        val audio = sine(0.8, 1000.0, 30) // ≈ −1,9 LUFS
        val chunk = 4096
        var i = 0
        while (i < audio.size) {
            val len = minOf(chunk, audio.size - i)
            val part = audio.copyOfRange(i, i + len)
            n.process(part, len)
            part.copyInto(audio, i)
            i += len
        }
        assertEquals(-12.1, n.currentGainDb(), 0.6)
        // 5 detik terakhir sudah sekitar −14 LUFS.
        val tail = LoudnessMeter(rate, 2)
        audio.copyOfRange(audio.size - rate * 10, audio.size).forEach(tail::add)
        assertEquals(-14.0, tail.integrated()!!, 0.7)
    }

    @Test fun knownLoudnessAppliesImmediatelyAndLimiterPreventsClipping() {
        val n = Normalizer(rate, 2).apply { targetLufs = -11.0; beginTrack(-24.0) } // lagu pelan → +9 dB (maks)
        val audio = sine(0.3, 200.0, 2)
        audio[1000] = 0.9f // satu puncak tajam
        n.process(audio, audio.size)
        assertEquals(9.0, n.currentGainDb(), 0.01)
        assertTrue("tidak boleh pecah", audio.all { abs(it) <= 0.971f })
    }

    @Test fun disabledLeavesAudioUntouched() {
        val n = Normalizer(rate, 2).apply { enabled = false; beginTrack(-5.0) }
        val audio = sine(0.5, 440.0, 1)
        val copy = audio.copyOf()
        n.process(audio, audio.size)
        assertTrue(audio.contentEquals(copy))
    }

    /** Bandingkan dengan pengukur EBU R128 ffmpeg pada lagu sungguhan (kalau tersedia). */
    @Test fun matchesFfmpegOnRealSongs() {
        val dir = File("align.on").takeIf { it.exists() }?.readText()?.trim()?.let(::File)
        Assume.assumeTrue(dir != null)
        val expected = mapOf("song1" to -8.3, "song2" to -7.8)
        val out = StringBuilder()
        for ((name, want) in expected) {
            val m = LoudnessMeter(44_100, 2)
            File(dir, "$name.44100.2.pcm").inputStream().buffered(1 shl 16).use { input ->
                val bytes = ByteArray(1 shl 16)
                while (true) {
                    val r = input.read(bytes)
                    if (r <= 0) break
                    val sb = ByteBuffer.wrap(bytes, 0, r - r % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.hasRemaining()) m.add(sb.get() / 32768f)
                }
            }
            val got = m.integrated()!!
            out.appendLine("$name: Freease %.2f LUFS, ffmpeg %.1f LUFS".format(got, want))
            assertEquals(want, got, 0.3)
        }
        File("build/loudness.txt").writeText(out.toString())
    }
}
