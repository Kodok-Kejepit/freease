package com.freease.music

import com.freease.music.data.lyrics.LyricsRepository
import com.freease.music.data.model.Thumbs
import com.freease.music.data.model.TitleCleaner
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test
    fun splitsArtistAndTitle() {
        assertEquals("Kangen" to "Dewa 19", TitleCleaner.split("Dewa 19 - Kangen (Official Audio)", "GP Records"))
        assertEquals("Teh Hijau" to "TULUS", TitleCleaner.split("TULUS - Teh Hijau (Official Music Video)", "Tulus"))
        assertEquals(
            "Kangen" to "Dewa 19",
            TitleCleaner.split("Dewa 19 - Kangen | Official Music Video", "Aquarius Musikindo"),
        )
        assertEquals("Teh Hijau" to "Tulus", TitleCleaner.split("Teh Hijau", "Tulus - Topic"))
        assertEquals("Dan" to "Sheila On 7", TitleCleaner.split("Dan - Sheila On 7", "Sheila On 7"))
        assertEquals("Interaksi" to "TULUS", TitleCleaner.split("TULUS\u00A0-\u00A0Interaksi", "Tulus"))
        assertEquals("Monokrom" to "Tulus", TitleCleaner.split("Monokrom [Lirik] #tulus", "Tulus"))
    }

    @Test
    fun keepsMeaningfulBrackets() {
        assertEquals("Separuh Nafas (Live)", TitleCleaner.cleanTitle("Separuh Nafas (Live)"))
        assertEquals("Lagu (feat. Ari Lasso)", TitleCleaner.cleanTitle("Lagu (feat. Ari Lasso) [Official Video]"))
    }

    @Test
    fun parsesLrc() {
        val lines = LyricsRepository(OkHttpClient()).parseLrc(
            "[ar:x]\n[00:12.50]Satu\n[01:02.3]Dua\n[00:05]Nol\n",
        )
        assertEquals(listOf(5_000L, 12_500L, 62_300L), lines.map { it.timeMs })
        assertEquals("Satu", lines[1].text)
    }

    @Test
    fun upscalesMusicArtwork() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            Thumbs.upscale("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 544),
        )
        assertEquals("https://i.ytimg.com/vi/ID/hq720.jpg", Thumbs.best("ID", "https://i.ytimg.com/vi/ID/hqdefault.jpg?x"))
    }
}
