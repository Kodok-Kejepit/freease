package com.freease.music

import com.freease.music.playback.SongParams
import com.freease.music.playback.VocalRemover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class KaraokeModeTest {
    private val fs = 44100

    /** RMS kanal kiri setelah melewati penghilang vokal (detik pertama dibuang: masa transisi). */
    private fun rmsAfter(on: Boolean, gen: (Int) -> Pair<Float, Float>): Double {
        val r = VocalRemover(fs).apply { target = if (on) 1f else 0f }
        val n = fs * 2
        val buf = FloatArray(n * 2)
        for (i in 0 until n) { val (l, rr) = gen(i); buf[2 * i] = l; buf[2 * i + 1] = rr }
        r.process(buf, buf.size)
        var sum = 0.0
        for (i in fs until n) sum += buf[2 * i] * buf[2 * i].toDouble()
        return sqrt(sum / fs)
    }

    private fun sine(f: Double, i: Int, a: Double = 0.3) = (a * sin(2 * PI * f * i / fs)).toFloat()

    @Test
    fun centeredVocalRemoved_sidesAndBassKept() {
        // "Vokal" 1 kHz tepat di tengah → hampir hilang (> 30 dB lebih pelan).
        val vocalOn = rmsAfter(true) { i -> sine(1000.0, i).let { it to it } }
        val vocalOff = rmsAfter(false) { i -> sine(1000.0, i).let { it to it } }
        assertTrue("vokal $vocalOn vs $vocalOff", vocalOn < vocalOff * 0.03)

        // "Gitar" hanya di kiri → tetap terdengar.
        val guitar = rmsAfter(true) { i -> sine(700.0, i) to 0f }
        assertTrue("gitar $guitar", guitar > 0.15)

        // Bass 60 Hz di tengah → dipertahankan.
        val bassOn = rmsAfter(true) { i -> sine(60.0, i).let { it to it } }
        val bassOff = rmsAfter(false) { i -> sine(60.0, i).let { it to it } }
        assertTrue("bass $bassOn vs $bassOff", bassOn > bassOff * 0.8)
    }

    @Test
    fun semitones() {
        assertEquals(0, SongParams.semitones(1f))
        assertEquals(2, SongParams.semitones(SongParams.pitchOf(2)))
        assertEquals(-3, SongParams.semitones(SongParams.pitchOf(-3)))
        assertEquals(2f, SongParams.pitchOf(12), 0.001f)
    }
}
