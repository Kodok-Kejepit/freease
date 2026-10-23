package com.freease.music

import com.freease.music.data.lyrics.Romanizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomanizerTest {
    private fun r(s: String) = Romanizer.romanize(s)

    @Test fun korean() {
        assertEquals("saranghae", r("사랑해"))
        assertEquals("annyeonghaseyo", r("안녕하세요"))
        assertEquals("meogeo", r("먹어"))
        assertEquals("joa", r("좋아"))
        assertEquals("ilgeo", r("읽어"))
        assertEquals("isseo", r("있어"))
        assertEquals("hallae", r("할래"))
        assertEquals("Baby neoreul saranghae", r("Baby 너를 사랑해"))
        assertEquals("chaeyeongiga joahaneun raendeomgeim", r("채영이가 좋아하는 랜덤게임"))
    }

    @Test fun japaneseKana() {
        assertEquals("arigatou", r("ありがとう"))
        assertEquals("chotto", r("ちょっと"))
        assertEquals("kyariipamyupamyu", r("きゃりーぱみゅぱみゅ"))
        assertEquals("fan", r("ファン"))
        assertEquals("paatii", r("パーティー"))
        assertEquals("matcha", r("まっちゃ"))
        assertEquals("kon'ya", r("こんや"))
    }

    @Test fun skipsWhatItCannotReadReliably() {
        assertNull(r("東京の空"))     // kanji
        assertNull(r("Look at the stars"))
        assertNull(r("Perjalanan membawamu"))
    }
}
