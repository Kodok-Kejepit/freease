package com.freease.music

import com.freease.music.data.lyrics.Karaoke
import com.freease.music.data.lyrics.LrcLib
import com.freease.music.data.lyrics.LyricsRepository
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.LyricWord
import com.freease.music.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KaraokeTest {
    @Test fun parsesEnhancedLrcWordTimes() {
        val lines = LrcLib.parseLrc("[00:12.00] <00:12.00> Perjalanan <00:12.80> membawamu\n[00:16.00]Bertemu denganku")
        val first = lines[0]
        assertEquals("Perjalanan membawamu", first.text)
        assertEquals(listOf(LyricWord(12_000, 0, 10), LyricWord(12_800, 11, 20)), first.words)
        assertEquals("Perjalanan", first.text.substring(0, 10))
        assertNull(lines[1].words)
    }

    @Test fun wordTimesSurviveSaveAndLoad() {
        val l = Lyrics(LrcLib.parseLrc("[00:01.00]<00:01.00>a <00:01.50>b"), null)
        assertEquals(l, LyricsRepository.decode(LyricsRepository.encode(l)))
    }

    @Test fun fillsWordByWord() {
        val line = LrcLib.parseLrc("[00:10.00]<00:10.00>satu <00:11.00>dua")[0]
        assertEquals(0f, Karaoke.sungChars(line, 14_000, 9_000), 0.01f)
        assertEquals(2.5f, Karaoke.sungChars(line, 14_000, 10_500), 0.01f) // separuh "satu " (5 karakter)
        assertEquals(5f, Karaoke.sungChars(line, 14_000, 10_999), 0.05f)
        assertEquals(8f, Karaoke.sungChars(line, 14_000, 20_000), 0.01f)
    }

    @Test fun estimatesWhenNoWordTimes() {
        val line = LyricLine(10_000, "Sepertimu yang kucari") // 21 karakter → ±1,6 dtk
        assertEquals(0f, Karaoke.sungChars(line, 30_000, 10_000), 0.01f)
        val half = Karaoke.sungChars(line, 30_000, 10_000 + 21 * 75 / 2)
        assertEquals(10.5f, half, 0.2f)
        assertEquals(21f, Karaoke.sungChars(line, 30_000, 15_000), 0.01f)
        // Baris berikutnya datang lebih cepat: terisi penuh saat baris berikutnya mulai.
        assertEquals(21f, Karaoke.sungChars(line, 11_000, 11_000), 0.01f)
    }
}
