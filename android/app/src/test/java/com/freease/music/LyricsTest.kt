package com.freease.music

import com.freease.music.data.lyrics.HttpStatusException
import com.freease.music.data.lyrics.LrcEntry
import com.freease.music.data.lyrics.LrcLib
import com.freease.music.data.lyrics.LrcSource
import com.freease.music.data.lyrics.LyricsFinder
import com.freease.music.data.lyrics.LyricsMatch
import com.freease.music.data.lyrics.LyricsRepository
import com.freease.music.data.lyrics.LyricsState
import com.freease.music.data.lyrics.MusicLyrics
import com.freease.music.data.lyrics.MusicLyricsSource
import com.freease.music.data.lyrics.MusicTrack
import com.freease.music.data.lyrics.WatchInfo
import com.freease.music.data.lyrics.YtmParse
import com.freease.music.data.lyrics.align.alignedWith
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.ui.player.currentLineIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Contoh balasan ASLI (YouTube Music & LRCLIB, "Pura Pura Lupa" - Mahen) disimpan di test/resources/lyrics. */
private fun fixture(name: String): String =
    LyricsParseTest::class.java.getResource("/lyrics/$name")!!.readText()

private fun json(name: String) = JSONObject(fixture(name))

private val ATV = Song("tSMVAp6eouE", "Pura Pura Lupa", "Mahen", durationSec = 247)
private val OMV = Song("zPWA5w0Yxco", "Pura Pura Lupa", "Mahen", durationSec = 289)

class LyricsParseTest {
    @Test fun watchInfoKnowsVideoType() {
        val atv = YtmParse.watchInfo(json("next_atv.json"), "tSMVAp6eouE")
        assertEquals("MUSIC_VIDEO_TYPE_ATV", atv.videoType)
        assertTrue(atv.isAudioTrack)
        assertEquals("MPLYt_1cVEtxwDsL6-1", atv.lyricsId)
        val omv = YtmParse.watchInfo(json("next_omv.json"), "zPWA5w0Yxco")
        assertEquals("MUSIC_VIDEO_TYPE_OMV", omv.videoType)
        assertFalse(omv.isAudioTrack)
        assertEquals("MUSIC_VIDEO_TYPE_UGC", YtmParse.watchInfo(json("next_ugc.json"), "tQkEG30xYxA").videoType)
    }

    @Test fun parsesTimedLyrics() {
        val l = YtmParse.lyrics(json("lyrics_timed.json"))!!
        assertTrue(l.timed)
        assertEquals(43, l.lines.size)
        assertEquals(LyricLine(17_410, "Pernah aku jatuh hati"), l.lines[1])
        assertEquals("Sumber: LyricFind", l.source)
        assertEquals(l.lines.sortedBy { it.timeMs }, l.lines)
    }

    @Test fun parsesStaticLyricsOfMusicVideo() {
        val l = YtmParse.lyrics(json("lyrics_static.json"))!!
        assertFalse(l.timed)
        assertEquals("Pernah aku jatuh hati", l.lines.first().text)
    }

    @Test fun recognisesNoLyricsMessage() {
        assertTrue(YtmParse.isLyricsMessage(json("lyrics_message.json")))
        assertNull(YtmParse.lyrics(json("lyrics_message.json")))
        assertFalse(YtmParse.isLyricsMessage(json("lyrics_timed.json")))
    }

    @Test fun parsesWebLyricsFallback() {
        val l = YtmParse.webLyrics(json("lyrics_web.json"))!!
        assertFalse(l.timed)
        assertEquals("Pernah aku jatuh hati", l.lines.first().text)
    }

    @Test fun parsesSongSearch() {
        val tracks = YtmParse.songs(json("search_songs.json"))
        val first = tracks.first()
        assertEquals("tSMVAp6eouE", first.videoId)
        assertEquals("Pura Pura Lupa", first.title)
        assertEquals("Mahen", first.artists)
        assertEquals(247L, first.durationSec)
    }

    @Test fun parsesDurations() {
        assertEquals(247L, YtmParse.parseDuration("4.07"))
        assertEquals(247L, YtmParse.parseDuration("4:07"))
        assertEquals(3723L, YtmParse.parseDuration("1.02.03"))
        assertNull(YtmParse.parseDuration("371 jt pemutaran"))
        assertNull(YtmParse.parseDuration("1.234"))
        assertNull(YtmParse.parseDuration("1989"))
    }

    @Test fun parsesLrc() {
        val lines = LrcLib.parseLrc("[ar:x]\n[00:12.50]Satu\n[01:02.3]Dua\n[00:05]Nol\n")
        assertEquals(listOf(5_000L, 12_500L, 62_300L), lines.map { it.timeMs })
        assertEquals("Satu", lines[1].text)
    }
}

class LyricsMatchTest {
    private fun lrclibPuraPuraLupa(): List<LrcEntry> {
        val arr = JSONArray(fixture("lrclib_pura_pura_lupa.json"))
        return (0 until arr.length()).mapNotNull { LrcLib.entry(arr.getJSONObject(it)) }
    }

    @Test fun titleRules() {
        assertTrue(LyricsMatch.titleMatches("Pura Pura Lupa", "Pura-Pura Lupa"))
        assertTrue(LyricsMatch.titleMatches("Luka Yang Kurindu (feat. Mawar de Jongh)", "Luka Yang Kurindu"))
        assertTrue(LyricsMatch.titleMatches("Pura Pura Lupa", "Pura Pura Lupa (Official Lyric Video)"))
        assertFalse(LyricsMatch.titleMatches("Pura Pura Lupa", "Pura Pura Lupa (Acoustic Version)"))
        assertFalse(LyricsMatch.titleMatches("Pura Pura Lupa", "Pura Pura Lupa (8-Bit Mahen Emulation)"))
        assertFalse(LyricsMatch.titleMatches("Pura Pura Lupa", "Lupa"))
        assertFalse(LyricsMatch.titleMatches("Pura Pura Lupa", "Pura Pura Lupa Rupa Rupanya Sendiri"))
    }

    @Test fun artistRules() {
        assertTrue(LyricsMatch.artistMatches("Mahen", "Song, Mahen"))
        assertTrue(LyricsMatch.artistMatches("Lady Gaga & Bruno Mars", "Lady Gaga, Bruno Mars"))
        assertFalse(LyricsMatch.artistMatches("Mahen", "Metha Zulia (cover)"))
        assertFalse(LyricsMatch.artistMatches("Mahen", "8-Bit Arcade"))
    }

    @Test fun findsTheRealAudioTrackNotTheAcousticOne() {
        val tracks = YtmParse.songs(json("search_songs.json"))
        assertEquals("tSMVAp6eouE", LyricsMatch.bestTrack(tracks, "Pura Pura Lupa", "Mahen")?.videoId)
        val acoustic = listOf(MusicTrack("r1", "Pura Pura Lupa (Acoustic Version)", "Mahen", 254))
        assertNull(LyricsMatch.bestTrack(acoustic, "Pura Pura Lupa", "Mahen"))
    }

    @Test fun longVideoTitlesAndSwappedTitleArtist() {
        // Judul MV panjang: judul lagu ada utuh di dalamnya dan nama artis tertulis di judul.
        assertTrue(LyricsMatch.songMatches("NewJeans (뉴진스) 'Hype Boy' Official MV (Performance ver.1)", "HYBE LABELS", "Hype Boy", "NewJeans"))
        // "Kangen - Dewa 19" terbaca terbalik → dicoba ditukar.
        val atv = listOf(MusicTrack("YKK5_OQiEa4", "Kangen", "Dewa 19", 304))
        assertEquals("YKK5_OQiEa4", LyricsMatch.bestTrack(atv, "Dewa 19", "Kangen")?.videoId)
        // Cover oleh orang lain (nama artis asli tidak ada) dan judul lain tidak ikut cocok.
        assertFalse(LyricsMatch.songMatches("Hype Boy (Cover)", "Fans", "Hype Boy", "NewJeans"))
        assertFalse(LyricsMatch.songMatches("Lagu Galau Terbaru", "Kumpulan", "Hype Boy", "NewJeans"))
    }

    @Test fun versionAndCompilationGuards() {
        assertTrue(LyricsMatch.isOtherVersion("Pura Pura Lupa (Sejawat Band Cover)", "Pura Pura Lupa"))
        assertTrue(LyricsMatch.isOtherVersion("NewJeans 'Hype Boy' Official MV (Performance ver.1)", "Hype Boy"))
        assertFalse(LyricsMatch.isOtherVersion("Pura Pura Lupa", "Pura Pura Lupa"))
        assertTrue(LyricsMatch.isCompilation(2400, 247))
        assertFalse(LyricsMatch.isCompilation(289, 247))
    }

    @Test fun lrclibMajorityDurationBeatsMislabeledEntries() {
        // Data asli: salinan lirik yang sama diberi durasi 4, 15, 228, 244, 247, 282, 289...
        val pick = LyricsMatch.pickLrc(lrclibPuraPuraLupa(), "Pura Pura Lupa", "Mahen", 289)!!
        assertNotNull(pick.entry.synced)
        assertEquals(247L, pick.typicalDurationSec)
        // Video klip 289 dtk: waktu lirik versi audio TIDAK boleh langsung diikuti (penyebab "lirik maju duluan").
        assertFalse(LyricsMatch.trustLrc(pick, 289, isAudioTrack = false, refDurationSec = null))
        assertFalse(LyricsMatch.trustLrc(pick, 289, isAudioTrack = false, refDurationSec = 247))
    }

    @Test fun lrclibTrustedForTheAudioVersion() {
        val pick = LyricsMatch.pickLrc(lrclibPuraPuraLupa(), "Pura Pura Lupa", "Mahen", 247)!!
        assertTrue(LyricsMatch.trustLrc(pick, 247, isAudioTrack = true, refDurationSec = null))
        assertTrue(LyricsMatch.trustLrc(pick, 247, isAudioTrack = false, refDurationSec = null))
        assertTrue(LyricsMatch.trustLrc(pick, 248, isAudioTrack = false, refDurationSec = 247))
    }

    @Test fun lrclibRejectsCoversAndOtherArtists() {
        val entries = listOf(
            LrcEntry(1, "PURA PURA LUPA - MAHEN", "Metha Zulia (cover)", 227.0, listOf(LyricLine(1000, "a")), "a"),
            LrcEntry(2, "Pura Pura Lupa", "8-Bit Arcade", 242.0, null, "a"),
        )
        assertNull(LyricsMatch.pickLrc(entries, "Pura Pura Lupa", "Mahen", 247))
    }

    @Test fun lineIndexUsesOffsetAndBinarySearch() {
        val l = Lyrics(listOf(LyricLine(1000, "a"), LyricLine(5000, "b"), LyricLine(9000, "c")), null)
        assertEquals(-1, currentLineIndex(l, 500, 0))
        assertEquals(0, currentLineIndex(l, 1000, 0))
        assertEquals(1, currentLineIndex(l, 5000, 0))
        assertEquals(0, currentLineIndex(l, 5000, 1000))
        assertEquals(2, currentLineIndex(l, 60_000, 0))
        // Video klip dengan intro 42 dtk: baris pertama baru menyala di detik 43.
        assertEquals(-1, currentLineIndex(l, 42_500, 42_000))
        assertEquals(0, currentLineIndex(l, 43_000, 42_000))
    }
}

/** Sumber tiruan berbasis data asli. */
private class FakeYtm(
    val info: Map<String, WatchInfo> = emptyMap(),
    val lyrics: Map<String, MusicLyrics> = emptyMap(),
    val search: List<MusicTrack> = emptyList(),
    val fail: IOException? = null,
) : MusicLyricsSource {
    val calls = AtomicInteger()
    override fun watchInfo(videoId: String): WatchInfo {
        calls.incrementAndGet(); fail?.let { throw it }
        return info[videoId] ?: WatchInfo(null, null)
    }
    override fun lyrics(lyricsId: String): MusicLyrics? { fail?.let { throw it }; return lyrics[lyricsId] }
    override fun searchSongs(query: String): List<MusicTrack> { fail?.let { throw it }; return search }
}

private class FakeLrc(val entries: List<LrcEntry> = emptyList(), val fail: IOException? = null) : LrcSource {
    val calls = AtomicInteger()
    override fun search(title: String, artist: String): List<LrcEntry> {
        calls.incrementAndGet(); fail?.let { throw it }; return entries
    }
    override fun searchQuery(q: String): List<LrcEntry> {
        calls.incrementAndGet(); fail?.let { throw it }; return entries
    }
}

class LyricsFinderTest {
    private val timed = YtmParse.lyrics(json("lyrics_timed.json"))!!
    private val static = YtmParse.lyrics(json("lyrics_static.json"))!!
    private val tracks = YtmParse.songs(json("search_songs.json"))
    private val ytm = FakeYtm(
        info = mapOf(
            ATV.id to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_ATV"),
            OMV.id to WatchInfo("MUSIC_VIDEO_TYPE_OMV", "L_OMV"),
        ),
        lyrics = mapOf("L_ATV" to timed, "L_OMV" to static),
        search = tracks,
    )

    @Test fun audioTrackUsesItsOwnTimedLyrics() {
        val lrc = FakeLrc()
        val l = LyricsFinder(ytm, lrc).find(ATV)
        assertTrue(l.canFollow)
        assertEquals(17_410L, l.synced!![1].timeMs)
        assertEquals("Sumber: LyricFind", l.source)
        assertEquals(0, lrc.calls.get())
    }

    @Test fun musicVideoWithIntroIsNotAutoSynced() {
        // MV 289 dtk vs versi audio 247 dtk: teks benar, tapi baris tidak boleh langsung mengikuti.
        val l = LyricsFinder(ytm, FakeLrc()).find(OMV)
        assertNotNull(l.synced)
        assertFalse(l.timingTrusted)
        assertFalse(l.canFollow)
        assertTrue(l.copy(userSynced = true, offsetMs = 30_000).canFollow)
    }

    @Test fun musicVideoWithSameLengthIsSynced() {
        val sameCut = Song("vid1", "Pura Pura Lupa", "Mahen", durationSec = 248)
        val y = FakeYtm(info = mapOf("vid1" to WatchInfo("MUSIC_VIDEO_TYPE_OMV", null), ATV.id to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_ATV")), lyrics = mapOf("L_ATV" to timed), search = tracks)
        val l = LyricsFinder(y, FakeLrc()).find(sameCut)
        assertTrue(l.canFollow)
    }

    @Test fun fanUploadFallsBackToLrclib() {
        val ugc = Song("ugc1", "Lagu Langka", "Band Indie", durationSec = 200)
        val entry = LrcEntry(7, "Lagu Langka", "Band Indie", 200.0, listOf(LyricLine(5000, "halo")), "halo")
        val l = LyricsFinder(FakeYtm(), FakeLrc(listOf(entry))).find(ugc)
        assertEquals("Sumber: LRCLIB", l.source)
        assertTrue(l.canFollow)
    }

    @Test fun swappedCoverUploadGetsOriginalWordsButNotAutoSync() {
        // Data asli: "Kangen - Dewa 19 | Akustik Cover" (267 dtk) terbaca judul "Dewa 19", artis "Kangen".
        val cover = Song("AX-GvJRKOXQ", "Dewa 19", "Kangen", durationSec = 267)
        val y = FakeYtm(
            info = mapOf(cover.id to WatchInfo("MUSIC_VIDEO_TYPE_UGC", null), "YKK5_OQiEa4" to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_K")),
            lyrics = mapOf("L_K" to timed),
            search = listOf(MusicTrack("YKK5_OQiEa4", "Kangen", "Dewa 19", 304)),
        )
        val l = LyricsFinder(y, FakeLrc()).find(cover)
        assertNotNull(l.synced)
        assertFalse(l.canFollow)
    }

    @Test fun performanceVersionIsNotAutoSyncedEvenWithSimilarLength() {
        val mv = Song("mv1", "NewJeans (뉴진스) 'Hype Boy' Official MV (Performance ver.1)", "HYBE LABELS", durationSec = 178)
        val y = FakeYtm(
            info = mapOf(mv.id to WatchInfo("MUSIC_VIDEO_TYPE_OMV", null), "ghrlZIMDzbM" to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_H")),
            lyrics = mapOf("L_H" to timed),
            search = listOf(MusicTrack("ghrlZIMDzbM", "Hype Boy", "NewJeans", 180)),
        )
        val l = LyricsFinder(y, FakeLrc()).find(mv)
        assertNotNull(l.synced)
        assertFalse(l.canFollow)
    }

    @Test fun compilationVideoDoesNotBorrowOneSongsLyrics() {
        val mix = Song("mix1", "Mahen Full Album: Pura Pura Lupa, Luka Yang Kurindu", "Mahen", durationSec = 2400)
        assertTrue(LyricsFinder(ytm, FakeLrc()).find(mix).isEmpty)
        // Data asli dari probe: video full album 2327 dtk berjudul "PURA PURA LUPA" sempat dapat lirik LRCLIB.
        val arr = JSONArray(fixture("lrclib_pura_pura_lupa.json"))
        val entries = (0 until arr.length()).mapNotNull { LrcLib.entry(arr.getJSONObject(it)) }
        val album = Song("album1", "PURA PURA LUPA", "Mahen", durationSec = 2327)
        assertTrue(LyricsFinder(FakeYtm(), FakeLrc(entries)).find(album).isEmpty)
    }

    @Test fun networkFailureIsNotReportedAsNoLyrics() {
        val down = IOException("offline")
        try {
            LyricsFinder(FakeYtm(fail = down), FakeLrc(fail = down)).find(ATV)
            throw AssertionError("harus melempar IOException")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun rejectedClientIsNotANetworkFailure() {
        // YouTube Music menolak (403) dan LRCLIB tidak punya: hasilnya "tidak ada lirik", bukan "gagal jaringan".
        val l = LyricsFinder(FakeYtm(fail = HttpStatusException(403, "forbidden")), FakeLrc()).find(ATV)
        assertTrue(l.isEmpty)
    }

    @Test fun lrclibStillWorksWhenYouTubeMusicIsDown() {
        val arr = JSONArray(fixture("lrclib_pura_pura_lupa.json"))
        val entries = (0 until arr.length()).mapNotNull { LrcLib.entry(arr.getJSONObject(it)) }
        val l = LyricsFinder(FakeYtm(fail = IOException("timeout")), FakeLrc(entries)).find(ATV)
        assertNotNull(l.synced)
        assertTrue(l.timingTrusted)
        val mv = LyricsFinder(FakeYtm(fail = IOException("timeout")), FakeLrc(entries)).find(OMV)
        assertFalse(mv.timingTrusted)
    }
}

class LyricsRepositoryTest {
    /** Folder "lyrics3" di dalam folder sementara sendiri (pembersihan folder lama hanya menyentuh induknya). */
    private fun tempDir() = java.io.File(Files.createTempDirectory("lyr").toFile(), "lyrics3")

    private class CountingFinder(
        private val result: () -> Lyrics,
        private val gate: CountDownLatch? = null,
    ) : MusicLyricsSource {
        val calls = AtomicInteger()
        override fun watchInfo(videoId: String): WatchInfo {
            calls.incrementAndGet()
            gate?.await(5, TimeUnit.SECONDS)
            return WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L")
        }
        override fun lyrics(lyricsId: String): MusicLyrics? {
            val l = result()
            return MusicLyrics(l.synced ?: return null, true, "Sumber: LyricFind")
        }
        override fun searchSongs(query: String): List<MusicTrack> = emptyList()
    }

    private val good = Lyrics(listOf(LyricLine(1000, "satu"), LyricLine(2000, "dua")), null)

    @Test fun cardAndFullScreenShareOneRequestAndOneState() {
        val gate = CountDownLatch(1)
        val src = CountingFinder({ good }, gate)
        val exec = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val repo = LyricsRepository(LyricsFinder(src, FakeLrc()), tempDir(), exec)
        val cardState = repo.state(ATV.id)
        val screenState = repo.state(ATV.id)
        repo.ensure(ATV)
        repo.ensure(ATV) // layar penuh dibuka saat kartu masih mencari
        assertEquals(LyricsState.Loading, cardState.value)
        gate.countDown()
        runBlocking { repo.get(ATV) }
        assertEquals(1, src.calls.get())
        assertTrue(cardState === screenState)
        assertTrue((cardState.value as LyricsState.Ready).lyrics.canFollow)
        exec.close()
    }

    @Test fun networkFailureShowsRetryAndRetrySucceeds() {
        var online = false
        val ytm = object : MusicLyricsSource {
            override fun watchInfo(videoId: String): WatchInfo {
                if (!online) throw IOException("offline")
                return WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L")
            }
            override fun lyrics(lyricsId: String) = MusicLyrics(good.synced!!, true, "Sumber: LyricFind")
            override fun searchSongs(query: String): List<MusicTrack> = emptyList()
        }
        val repo = LyricsRepository(LyricsFinder(ytm, FakeLrc(fail = IOException("offline"))), tempDir(), Dispatchers.Unconfined)
        val state = repo.state(ATV.id)
        repo.ensure(ATV)
        assertEquals(LyricsState.Failed, state.value)
        online = true
        repo.ensure(ATV) // dibuka lagi → otomatis mencoba lagi
        assertTrue(state.value is LyricsState.Ready)
    }

    @Test fun failedRefreshKeepsExistingLyrics() {
        var online = true
        val ytm = object : MusicLyricsSource {
            override fun watchInfo(videoId: String): WatchInfo {
                if (!online) throw IOException("offline")
                return WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L")
            }
            override fun lyrics(lyricsId: String) = MusicLyrics(good.synced!!, true, "Sumber: LyricFind")
            override fun searchSongs(query: String): List<MusicTrack> = emptyList()
        }
        val repo = LyricsRepository(LyricsFinder(ytm, FakeLrc(fail = IOException("offline"))), tempDir(), Dispatchers.Unconfined)
        val state = repo.state(ATV.id)
        repo.ensure(ATV)
        online = false
        repo.refresh(ATV)
        assertEquals(good.synced, (state.value as LyricsState.Ready).lyrics.synced)
    }

    @Test fun emptyResultIsRememberedButRetriedWhenSongInfoChanges() {
        val src = CountingFinder({ Lyrics(null, null) })
        val repo = LyricsRepository(LyricsFinder(src, FakeLrc()), tempDir(), Dispatchers.Unconfined)
        repo.ensure(ATV)
        repo.ensure(ATV)
        assertEquals(1, src.calls.get())
        repo.ensure(ATV.copy(title = "Pura Pura Lupa (Live)"))
        assertEquals(2, src.calls.get())
    }

    @Test fun userSyncIsSavedToDiskAndRestored() {
        val dir = tempDir()
        val src = CountingFinder({ good })
        val repo = LyricsRepository(LyricsFinder(src, FakeLrc()), dir, Dispatchers.Unconfined)
        val state = repo.state(OMV.id)
        repo.ensure(OMV)
        repo.setSync(OMV.id, 42_000, userSynced = true)
        val l = (state.value as LyricsState.Ready).lyrics
        assertEquals(42_000L, l.offsetMs)
        assertTrue(l.userSynced)
        // Aplikasi dibuka ulang: dibaca dari disk tanpa internet.
        val again = LyricsRepository(LyricsFinder(FakeYtm(fail = IOException("offline")), FakeLrc()), dir, Dispatchers.Unconfined)
        val restored = runBlocking { again.get(OMV) }
        assertEquals(42_000L, restored.offsetMs)
        assertTrue(restored.userSynced)
        assertEquals(good.synced, restored.synced)
    }

    @Test fun encodeDecodeRoundTrip() {
        val l = Lyrics(listOf(LyricLine(1, "a")), "a", "Sumber: LRCLIB", offsetMs = -500, timingTrusted = false, userSynced = true)
        assertEquals(l, LyricsRepository.decode(LyricsRepository.encode(l)))
        val synced = l.copy(timingRefId = "ref", autoSync = Lyrics.AutoSync.DONE, approxTiming = true)
        assertEquals(synced, LyricsRepository.decode(LyricsRepository.encode(synced)))
    }
}

class LyricsAutoSyncTest {
    private val timed = YtmParse.lyrics(json("lyrics_timed.json"))!!
    private val static = YtmParse.lyrics(json("lyrics_static.json"))!!
    private val tracks = YtmParse.songs(json("search_songs.json"))
    private val ytm = FakeYtm(
        info = mapOf(
            ATV.id to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_ATV"),
            OMV.id to WatchInfo("MUSIC_VIDEO_TYPE_OMV", "L_OMV"),
        ),
        lyrics = mapOf("L_ATV" to timed, "L_OMV" to static),
        search = tracks,
    )

    private fun tempDir() = java.io.File(Files.createTempDirectory("lyr").toFile(), "lyrics3")

    /** Peta sederhana: seluruh lagu digeser [shiftMs] (mis. intro video klip). */
    private fun shifted(shiftMs: Long) = com.freease.music.data.lyrics.align.AlignmentMap(
        listOf(com.freease.music.data.lyrics.align.AlignmentMap.Segment(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, shiftMs)),
        confidence = 0.9,
        videoDurationMs = 289_000,
    )

    @Test fun musicVideoRemembersWhichRecordingTheTimingBelongsTo() {
        val l = LyricsFinder(ytm, FakeLrc()).find(OMV)
        assertFalse(l.canFollow)
        assertEquals(ATV.id, l.timingRefId)
        assertTrue(l.canAutoSync)
        // Versi audio sendiri tidak perlu diselaraskan.
        assertNull(LyricsFinder(ytm, FakeLrc()).find(ATV).timingRefId)
    }

    @Test fun musicVideoLyricsFollowAutomaticallyAfterAudioSync() {
        val calls = AtomicInteger()
        val dir = tempDir()
        val repo = LyricsRepository(
            LyricsFinder(ytm, FakeLrc()), dir, Dispatchers.Unconfined,
            autoSync = { video, ref, _ ->
                calls.incrementAndGet()
                assertEquals(OMV.id, video)
                assertEquals(ATV.id, ref)
                com.freease.music.data.lyrics.align.AutoSyncResult.Aligned(shifted(42_000))
            },
        )
        val state = repo.state(OMV.id)
        repo.ensure(OMV)
        val l = (state.value as LyricsState.Ready).lyrics
        assertTrue(l.canFollow)
        assertEquals(Lyrics.AutoSync.DONE, l.autoSync)
        assertEquals(timed.lines[1].timeMs + 42_000, l.synced!![1].timeMs)
        assertEquals(1, calls.get())

        // Dibuka lagi (app dimulai ulang): langsung dari disk, tidak diselaraskan ulang.
        val again = LyricsRepository(
            LyricsFinder(FakeYtm(fail = IOException("offline")), FakeLrc()), dir, Dispatchers.Unconfined,
            autoSync = { _, _, _ -> calls.incrementAndGet(); error("tidak boleh dipanggil") },
        )
        val s2 = again.state(OMV.id)
        again.ensure(OMV)
        assertTrue((s2.value as LyricsState.Ready).lyrics.canFollow)
        assertEquals(1, calls.get())
    }

    @Test fun differentRecordingFallsBackToTapSyncWithoutRetrying() {
        val calls = AtomicInteger()
        val repo = LyricsRepository(
            LyricsFinder(ytm, FakeLrc()), tempDir(), Dispatchers.Unconfined,
            autoSync = { _, _, _ -> calls.incrementAndGet(); com.freease.music.data.lyrics.align.AutoSyncResult.NotMatching },
        )
        val state = repo.state(OMV.id)
        repo.ensure(OMV)
        repo.ensure(OMV)
        val ready = state.value as LyricsState.Ready
        assertFalse(ready.syncing)
        assertFalse(ready.lyrics.canFollow)
        assertEquals(Lyrics.AutoSync.NOT_MATCHING, ready.lyrics.autoSync)
        assertEquals(1, calls.get())
    }

    @Test fun networkErrorRetriesOnlyAfterAWhile() {
        var now = 1_000_000L
        val calls = AtomicInteger()
        val repo = LyricsRepository(
            LyricsFinder(ytm, FakeLrc()), tempDir(), Dispatchers.Unconfined, clock = { now },
            autoSync = { _, _, _ -> calls.incrementAndGet(); com.freease.music.data.lyrics.align.AutoSyncResult.Error(IOException("offline")) },
        )
        val state = repo.state(OMV.id)
        repo.ensure(OMV)
        repo.ensure(OMV)
        assertEquals(1, calls.get())
        assertEquals(Lyrics.AutoSync.NONE, (state.value as LyricsState.Ready).lyrics.autoSync)
        now += 3 * 60_000
        repo.ensure(OMV)
        assertEquals(2, calls.get())
    }

    @Test fun userTapDuringSyncWins() {
        val gate = CountDownLatch(1)
        val exec = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val done = CountDownLatch(1)
        val repo = LyricsRepository(
            LyricsFinder(ytm, FakeLrc()), tempDir(), exec,
            autoSync = { _, _, _ ->
                gate.await(5, TimeUnit.SECONDS)
                com.freease.music.data.lyrics.align.AutoSyncResult.Aligned(shifted(42_000)).also { done.countDown() }
            },
        )
        val state = repo.state(OMV.id)
        repo.ensure(OMV)
        runBlocking { repo.get(OMV) }
        val deadline = System.currentTimeMillis() + 3_000
        while ((state.value as? LyricsState.Ready)?.syncing != true && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue((state.value as LyricsState.Ready).syncing)
        repo.setSync(OMV.id, 30_000, userSynced = true)
        gate.countDown()
        done.await(5, TimeUnit.SECONDS)
        Thread.sleep(100)
        val l = (state.value as LyricsState.Ready).lyrics
        assertEquals(30_000L, l.offsetMs)
        assertEquals(timed.lines[1].timeMs, l.synced!![1].timeMs)
        exec.close()
    }

    @Test fun nearlySameLengthVideoFollowsRightAwayWhileSyncing() {
        // Video 250 dtk vs versi audio 247 dtk: lirik langsung berjalan (perkiraan), lalu diselaraskan.
        val near = Song("vidNear", "Pura Pura Lupa", "Mahen", durationSec = 252)
        val y = FakeYtm(
            info = mapOf("vidNear" to WatchInfo("MUSIC_VIDEO_TYPE_OMV", null), ATV.id to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_ATV")),
            lyrics = mapOf("L_ATV" to timed), search = tracks,
        )
        val l = LyricsFinder(y, FakeLrc()).find(near)
        assertFalse(l.timingTrusted)
        assertTrue(l.approxTiming)
        assertTrue(l.canFollow)
        assertFalse(l.exactTiming)
        assertTrue(l.canAutoSync)
        // Video live tidak ikut berjalan walau durasinya mirip.
        val live = near.copy(id = "vidLive", title = "Pura Pura Lupa (Live)")
        val y2 = FakeYtm(
            info = mapOf("vidLive" to WatchInfo("MUSIC_VIDEO_TYPE_OMV", null), ATV.id to WatchInfo("MUSIC_VIDEO_TYPE_ATV", "L_ATV")),
            lyrics = mapOf("L_ATV" to timed), search = tracks,
        )
        assertFalse(LyricsFinder(y2, FakeLrc()).find(live).approxTiming)
    }

    @Test fun cutPartOfTheSongIsSkippedNotShownAtTheWrongTime() {
        val l = Lyrics((0 until 10).map { LyricLine(it * 10_000L, "baris $it") }, null, timingTrusted = false, timingRefId = "ref")
        // Video: intro 5 dtk; bagian acuan 40–60 dtk dipotong.
        val map = com.freease.music.data.lyrics.align.AlignmentMap(
            listOf(
                com.freease.music.data.lyrics.align.AlignmentMap.Segment(Long.MIN_VALUE / 4, 40_000, 5_000),
                com.freease.music.data.lyrics.align.AlignmentMap.Segment(60_000, Long.MAX_VALUE / 4, -15_000),
            ),
            confidence = 0.9, videoDurationMs = 200_000,
        )
        val out = l.alignedWith(map)!!
        assertEquals(listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L, 55_000L, 65_000L, 75_000L), out.synced!!.map { it.timeMs })
        assertTrue(out.canFollow)
    }
}

/** Penyelaras dengan audio sintetis (nada + ketukan acak), tanpa berkas dari luar. */
class AlignerTest {
    private val rate = 16_000

    /** Lagu acak yang deterministik: nada dari tangga nada + ketukan noise. */
    private fun song(seconds: Int, seed: Long): FloatArray {
        val rnd = java.util.Random(seed)
        val out = FloatArray(seconds * rate)
        val scale = doubleArrayOf(220.0, 247.0, 262.0, 294.0, 330.0, 349.0, 392.0, 440.0, 494.0, 523.0)
        var t = 0
        while (t < out.size) {
            val len = (rate * (0.12 + rnd.nextDouble() * 0.4)).toInt()
            val f = scale[rnd.nextInt(scale.size)] * (if (rnd.nextBoolean()) 2 else 1)
            val drum = rnd.nextInt(3) == 0
            for (i in 0 until len) {
                if (t + i >= out.size) break
                val env = Math.exp(-i / (rate * 0.15))
                var s = 0.3 * env * Math.sin(2 * Math.PI * f * i / rate)
                if (drum && i < rate / 20) s += 0.4 * (rnd.nextDouble() * 2 - 1) * Math.exp(-i / (rate * 0.01))
                out[t + i] += s.toFloat()
            }
            t += len
        }
        return out
    }

    private fun features(pcm: FloatArray): com.freease.music.data.lyrics.align.AudioFeatures {
        val ex = com.freease.music.data.lyrics.align.AudioFeatureExtractor(rate, 1)
        ex.feed(pcm, pcm.size)
        return ex.finish()
    }

    @Test fun findsIntroShiftAndRejectsOtherSong() {
        val ref = song(90, 1)
        val intro = song(9, 99).map { it * 0.5f }
        val video = (intro + ref.map { it * 0.7f }).toFloatArray()
        val map = com.freease.music.data.lyrics.align.Aligner.align(features(ref), features(video))!!
        val introMs = 9_000L
        for (t in listOf(2_000L, 45_000L, 85_000L)) {
            val got = map.map(t)!!
            assertTrue("acuan $t → $got", kotlin.math.abs(got - (t + introMs)) <= 40)
        }
        assertNull(com.freease.music.data.lyrics.align.Aligner.align(features(ref), features(song(90, 7))))
    }

    @Test fun fftMatchesNaiveDft() {
        val n = 64
        val re = FloatArray(n) { kotlin.math.sin(it * 0.7f) + 0.3f * kotlin.math.cos(it * 2.1f) }
        val im = FloatArray(n)
        val expRe = DoubleArray(n)
        val expIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2 * Math.PI * k * t / n
            expRe[k] += re[t] * Math.cos(a)
            expIm[k] += re[t] * Math.sin(a)
        }
        com.freease.music.data.lyrics.align.Fft(n).transform(re, im)
        for (k in 0 until n) {
            assertEquals(expRe[k], re[k].toDouble(), 1e-3)
            assertEquals(expIm[k], im[k].toDouble(), 1e-3)
        }
    }
}
