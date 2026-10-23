package com.freease.music

import com.freease.music.data.recognize.SignatureGenerator
import com.freease.music.data.recognize.SongRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class RecognizeTest {
    /** Beberapa nada yang berganti tiap 0,25 detik + sedikit derau — cukup untuk menghasilkan puncak. */
    private fun tones(seconds: Int): ShortArray {
        val rnd = Random(7)
        val freqs = doubleArrayOf(330.0, 440.0, 660.0, 990.0, 1320.0, 2200.0, 3300.0, 4400.0)
        return ShortArray(16000 * seconds) { i ->
            val seg = i / 4000
            val f1 = freqs[seg % freqs.size]
            val f2 = freqs[(seg * 3 + 1) % freqs.size]
            val v = 6000 * sin(2 * PI * f1 * i / 16000) + 4000 * sin(2 * PI * f2 * i / 16000) + rnd.nextInt(-300, 300)
            v.toInt().toShort()
        }
    }

    @Test
    fun signatureHeaderAndCrc() {
        val gen = SignatureGenerator()
        gen.feed(tones(6))
        assertTrue("peaks=${gen.peakCount}", gen.peakCount > 20)
        val sig = gen.encode()
        val b = ByteBuffer.wrap(sig).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), b.getInt(0))
        assertEquals(0x94119c00.toInt(), b.getInt(12))
        assertEquals(sig.size - 48, b.getInt(8))
        assertEquals(3 shl 27, b.getInt(28))
        val crc = CRC32().apply { update(sig, 8, sig.size - 8) }.value.toInt()
        assertEquals(crc, b.getInt(4))
        // Snapshot menghasilkan sidik jari yang sama.
        assertTrue(sig.contentEquals(gen.snapshot().encode()))
    }

    @Test
    fun parseMatchAndNoMatch() {
        val json = """
            {"matches":[{"id":"1","offset":40.8}],"track":{"title":"In The Mood","subtitle":"Glenn Miller",
             "images":{"coverart":"https://x/c.jpg","coverarthq":"https://x/hq.jpg"},
             "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Forever"},{"title":"Released","text":"1990"}]}]}}
        """.trimIndent()
        val s = SongRecognizer.parse(json)!!
        assertEquals("In The Mood", s.title)
        assertEquals("Glenn Miller", s.artist)
        assertEquals("https://x/hq.jpg", s.coverUrl)
        assertEquals("Forever", s.album)
        assertEquals("1990", s.year)
        assertEquals(40.8, s.offsetSec!!, 0.01)
        assertNull(SongRecognizer.parse("""{"matches":[],"retryms":3000}"""))
    }
}
