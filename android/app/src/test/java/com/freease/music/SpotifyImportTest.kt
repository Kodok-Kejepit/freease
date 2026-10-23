package com.freease.music

import com.freease.music.data.importer.SpotifyReader
import com.freease.music.data.importer.SpotifyTrack
import com.freease.music.data.importer.TrackMatcher
import com.freease.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyImportTest {
    private fun page(name: String) = SpotifyImportTest::class.java.getResource("/spotify/$name.html")!!.readText()

    @Test fun parsesLinksInEveryCommonShape() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        for (text in listOf(
            "https://open.spotify.com/playlist/$id?si=abc123",
            "Dengerin ini! https://open.spotify.com/intl-id/playlist/$id",
            "https://open.spotify.com/embed/playlist/$id",
            "spotify:playlist:$id",
        )) {
            val ref = SpotifyReader.parseLink(text)!!
            assertEquals("playlist", ref.type)
            assertEquals(id, ref.id)
        }
        assertEquals("album", SpotifyReader.parseLink("https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa")!!.type)
        assertTrue(SpotifyReader.isSpotifyLink("https://spotify.link/AbCdEf"))
        assertNull(SpotifyReader.parseLink("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun readsRealEmbedPages() {
        val pl = SpotifyReader.parse(page("playlist_37i9dQZF1DXcBWIGoYBM5M"), "playlist")
        assertEquals("Today’s Top Hits", pl.name)
        assertEquals(50, pl.tracks.size)
        assertFalse(pl.truncated)
        val first = pl.tracks[0]
        assertEquals("Housewife", first.title)
        assertEquals("Tate McRae", first.artists)
        assertEquals(208_810L, first.durationMs)
        // Spasi tak-terputus di antara nama artis dirapikan.
        assertTrue(pl.tracks.none { it.artists.contains(' ') })

        val album = SpotifyReader.parse(page("album_4m2880jivSbbyEGAKfITCa"), "album")
        assertEquals("Random Access Memories", album.name)
        assertEquals(13, album.tracks.size)

        val track = SpotifyReader.parse(page("track_0VjIjW4GlUZAMYd2vXMi3b"), "track")
        assertEquals(listOf(SpotifyTrack("Blinding Lights", "The Weeknd", 200_040)), track.tracks)
    }

    private fun song(id: String, title: String, artist: String, dur: Long) = Song(id, title, artist, durationSec = dur)

    @Test fun picksTheRightRecording() {
        val t = SpotifyTrack("Blinding Lights", "The Weeknd", 200_040)
        val picked = TrackMatcher.best(t, listOf(
            song("a", "Blinding Lights (Live)", "The Weeknd", 210),
            song("b", "THE WEEKND FULL ALBUM", "Mix", 3600),
            song("c", "Blinding Lights", "The Weeknd - Topic", 201),
            song("d", "Blinding Lights", "The Weeknd", 263),
        ))
        assertEquals("c", picked?.id)
        // Tidak ada yang cocok: lebih baik dilewati daripada salah lagu.
        assertNull(TrackMatcher.best(t, listOf(song("x", "Save Your Tears", "The Weeknd", 215))))
    }

    @Test fun prefersOfficialUploadOverFanVideo() {
        val t = SpotifyTrack("hate that i made you love me", "Ariana Grande", 197_000)
        val picked = TrackMatcher.best(t, listOf(
            song("fan", "【和訳】hate that i made you love me", "エル", 198),
            song("off", "hate that i made you love me", "Ariana Grande", 198),
        ))
        assertEquals("off", picked?.id)
    }

    @Test fun missingPlaylistGivesClearMessage() {
        val html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"status\":404}}}</script>"
        val e = runCatching { SpotifyReader.parse(html, "playlist") }.exceptionOrNull()
        assertTrue(e?.message!!.contains("tidak ditemukan"))
    }

    @Test fun acceptsMusicVideoWhenNoAudioVersion() {
        val t = SpotifyTrack("Hati-Hati di Jalan", "Tulus", 242_000)
        val picked = TrackMatcher.best(t, listOf(song("mv", "Hati-Hati di Jalan", "TULUS", 243)))
        assertEquals("mv", picked?.id)
    }
}
