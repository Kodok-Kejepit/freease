package com.freease.music

import com.freease.music.data.db.TasteEvent
import com.freease.music.data.model.Song
import com.freease.music.data.taste.ArtistSignal
import com.freease.music.data.taste.SongSignal
import com.freease.music.playback.smart.Candidate
import com.freease.music.playback.smart.PickContext
import com.freease.music.playback.smart.SmartScorer
import com.freease.music.playback.smart.SmartSession
import com.freease.music.playback.smart.Taste
import com.freease.music.playback.smart.TasteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600_000L

    private fun song(id: String, title: String, artist: String, sec: Long = 200) =
        Song(id = id, title = title, artist = artist, durationSec = sec)

    private fun event(id: String, artist: String, title: String, agoDays: Double, frac: Double = 1.0, sec: Long = 200) =
        TasteEvent(id, artist, title, now - (agoDays * day).toLong(), (sec * 1000 * frac).toLong(), sec)

    private fun ctx(queue: Set<String> = emptySet(), tail: List<String> = emptyList(), explore: Double = 0.4, skips: Map<String, Int> = emptyMap()) =
        PickContext(queue, tail, explore, skips)

    private fun profile(
        events: List<TasteEvent> = emptyList(),
        liked: List<Song> = emptyList(),
        songs: List<SongSignal> = emptyList(),
        artists: List<ArtistSignal> = emptyList(),
    ) = TasteProfile.build(now, events, liked, songs, artists, hour = 14)

    // ------------------------------------------------------------ Taste

    @Test fun artistKeyIgnoresCaseAndPunctuation() {
        assertEquals(Taste.key("Dewa 19"), Taste.key("dewa-19!"))
    }

    @Test fun classifySkipAndComplete() {
        assertEquals(Taste.Outcome.SKIP, Taste.classify(200, 5_000, manualSkip = true, autoEnd = false))
        assertEquals(Taste.Outcome.SKIP, Taste.classify(200, 60_000, manualSkip = true, autoEnd = false))
        assertNull(Taste.classify(200, 150_000, manualSkip = true, autoEnd = false)) // 75% lalu ditekan next: netral
        assertEquals(Taste.Outcome.COMPLETE, Taste.classify(200, 190_000, manualSkip = false, autoEnd = true))
        assertEquals(Taste.Outcome.COMPLETE, Taste.classify(200, 170_000, manualSkip = false, autoEnd = false))
        assertNull(Taste.classify(200, 20_000, manualSkip = false, autoEnd = false)) // diganti lewat daftar: netral
    }

    @Test fun tagsDetectStyleScriptAndIndonesian() {
        assertTrue("acoustic" in Taste.tagsOf("Kangen (Acoustic Version)"))
        assertTrue("script:hangul" in Taste.tagsOf("방탄소년단 봄날"))
        assertTrue("script:kana" in Taste.tagsOf("夜に駆ける ヨルシカ"))
        assertTrue("lang:id" in Taste.tagsOf("Aku dan Kamu untuk Selamanya"))
        assertFalse("lang:id" in Taste.tagsOf("Love Story"))
        assertTrue("script:latin" in Taste.tagsOf("Love Story"))
    }

    @Test fun noiseTitlesAreRejected() {
        assertTrue(Taste.isNoise("Lagu Pop Indonesia 10 HOURS nonstop"))
        assertTrue(Taste.isNoise("Cinta Karaoke Version"))
        assertFalse(Taste.isNoise("Kangen - Dewa 19"))
    }

    @Test fun bandsCoverTheDay() {
        assertEquals(Taste.Band.NIGHT, Taste.bandOf(23))
        assertEquals(Taste.Band.NIGHT, Taste.bandOf(3))
        assertEquals(Taste.Band.MORNING, Taste.bandOf(8))
        assertEquals(Taste.Band.DAY, Taste.bandOf(14))
        assertEquals(Taste.Band.EVENING, Taste.bandOf(19))
    }

    // ------------------------------------------------------------ profil

    @Test fun recentAndFrequentArtistsRankAbovePastOnes() {
        val ev = (1..10).map { event("a$it", "Dewa 19", "Lagu $it", agoDays = 1.0 + it) } +
            (1..10).map { event("b$it", "Artis Lama", "Lagu $it", agoDays = 80.0) }
        val p = profile(ev)
        assertTrue(p.artist.getValue(Taste.key("Dewa 19")) > p.artist.getValue(Taste.key("Artis Lama")))
    }

    @Test fun skipsAndDownsPushArtistNegative() {
        val ev = (1..6).map { event("a$it", "Band A", "Lagu $it", 1.0) } + event("b1", "Band B", "Satu", 1.0)
        val arts = listOf(ArtistSignal(Taste.key("Band B"), skips = 0, downs = 2, lastAt = now))
        val p = profile(ev, artists = arts)
        assertTrue(p.artist.getValue(Taste.key("Band B")) < 0)
    }

    @Test fun downedSongIsBlockedAndRecentSongIsExcluded() {
        val p = profile(
            events = listOf(event("just", "X", "Baru Saja", agoDays = 0.1)),
            songs = listOf(SongSignal("bad", downs = 1, lastAt = now)),
        )
        val c = ctx()
        assertFalse(SmartScorer.eligible(Candidate(song("bad", "Lagu Buruk", "Y"), 0.5, null), p, c))
        assertFalse(SmartScorer.eligible(Candidate(song("just", "Baru Saja", "X"), 0.5, null), p, c))
        assertTrue(SmartScorer.eligible(Candidate(song("ok", "Lagu Bagus", "Z"), 0.5, null), p, c))
    }

    // ------------------------------------------------------------ skor & pilihan

    @Test fun favoriteArtistOutscoresStranger() {
        val ev = (1..12).map { event("a$it", "Fave", "Lagu $it", 2.0) }
        val p = profile(ev)
        val fav = SmartScorer.score(Candidate(song("f1", "Lagu Baru", "Fave"), 0.3, null), p, ctx())
        val stranger = SmartScorer.score(Candidate(song("s1", "Lagu Baru", "Orang Asing"), 0.3, null), p, ctx())
        assertTrue(fav.score > stranger.score)
        assertTrue(fav.why.contains("Fave"))
    }

    @Test fun closeToSeedBeatsFarFromSeedForColdStart() {
        val p = TasteProfile.EMPTY
        val near = SmartScorer.score(Candidate(song("n", "A", "P"), 0.9, "Lagu Seed"), p, ctx())
        val far = SmartScorer.score(Candidate(song("f", "B", "Q"), 0.1, "Lagu Seed"), p, ctx())
        assertTrue(near.score > far.score)
        assertTrue(near.why.contains("Lagu Seed"))
    }

    @Test fun tagFitPrefersStyleYouListenTo() {
        val ev = (1..12).map { event("k$it", "Artis$it", "Lagu Korea $it 방탄소년단", 1.0) }
        val p = profile(ev)
        val korean = SmartScorer.score(Candidate(song("k", "새로운 노래 봄날", "Baru"), 0.3, null), p, ctx())
        val latin = SmartScorer.score(Candidate(song("l", "Another Song", "Baru Juga"), 0.3, null), p, ctx())
        assertTrue(korean.score > latin.score)
    }

    @Test fun chooseAvoidsQueueDuplicatesNoiseAndSameArtistStreak() {
        val p = profile((1..8).map { event("a$it", "Fave", "Lagu $it", 1.0) })
        val cands = listOf(
            Candidate(song("q1", "Sudah di antrean", "Fave"), 0.9, null),
            Candidate(song("n1", "Lagu Karaoke Version", "Fave"), 0.9, null),
            Candidate(song("f1", "Lagu Fave Baru", "Fave"), 0.8, null),
            Candidate(song("f2", "Lagu Fave Lain", "Fave"), 0.8, null),
            Candidate(song("o1", "Lagu Orang", "Orang"), 0.8, null),
            Candidate(song("o1", "Lagu Orang", "Orang"), 0.8, null), // duplikat id
        )
        val picks = SmartScorer.choose(cands, p, ctx(queue = setOf("q1"), tail = listOf(Taste.key("Fave"), Taste.key("Fave"))), 3, Random(7))
        assertTrue(picks.none { it.id == "q1" || it.id == "n1" })
        assertEquals(picks.size, picks.map { it.id }.toSet().size)
        assertTrue(picks.all { it.isSmartPick })
        // Dua lagu terakhir di antrean sudah dari Fave → artis lain harus tampil lebih dulu.
        assertEquals("o1", picks.first().id)
    }

    @Test fun chooseReturnsEmptyWhenNothingEligible() {
        val picks = SmartScorer.choose(listOf(Candidate(song("x", "Live Stream", "Z").copy(isLive = true), 1.0, null)), TasteProfile.EMPTY, ctx(), 2)
        assertTrue(picks.isEmpty())
    }

    @Test fun sessionAdaptsToReactions() {
        val s = SmartSession()
        val base = s.explore
        repeat(4) { s.record(true) }
        assertTrue(s.explore > base)
        assertEquals(2, s.gap)
        val t = SmartSession()
        t.record(false); t.record(false)
        assertTrue(t.explore < base)
        assertEquals(5, t.gap) // dua penolakan berturut-turut → lebih jarang menyisipkan
        t.reset()
        assertEquals(base, t.explore, 1e-9)
    }

    @Test fun sessionSkipsPenalizeArtistImmediately() {
        val p = TasteProfile.EMPTY
        val c = Candidate(song("x", "Lagu", "Artis"), 0.5, null)
        val clean = SmartScorer.score(c, p, ctx()).score
        val skipped = SmartScorer.score(c, p, ctx(skips = mapOf(Taste.key("Artis") to 2))).score
        assertTrue(skipped < clean - 2.0)
        assertNotNull(SmartScorer.score(c, p, ctx()).why)
    }
}
