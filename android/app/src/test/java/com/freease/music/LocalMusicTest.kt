package com.freease.music

import com.freease.music.data.local.LocalSongs
import com.freease.music.data.model.Song
import com.freease.music.data.prefs.AudioQuality
import com.freease.music.playback.MediaItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMusicTest {
    private val song = Song("local:42", "Lagu Rekaman", "Band Sendiri", album = "Demo", durationSec = 200, thumbnail = "localart://42")

    @Test fun playsFromMediaStoreNeverFromYouTube() {
        val item = MediaItems.fromSong(song)
        for (video in listOf(false, true)) {
            val p = MediaItems.forPlayback(item, video, null, AudioQuality.MAX, 720)
            assertEquals("content://media/external/audio/media/42", p.localConfiguration!!.uri.toString())
            assertFalse(MediaItems.isVideo(p))
        }
        assertTrue(song.isLocal)
        assertTrue(LocalSongs.isLocal(MediaItems.toSong(item).id))
    }

    @Test fun artworkComesFromTheFile() {
        assertEquals("localart://42", song.artworkUrl)
        assertEquals("localart://42", song.artworkSmall)
        assertEquals("localart://42", Song("local:42", "a", "b").artworkUrl)
    }
}
