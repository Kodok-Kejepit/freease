package com.freease.music

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freease.music.data.model.Song
import com.freease.music.playback.LibraryTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTreeTest {
    @Test fun androidAutoCanBrowseAndPlayWholeFolders() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<FreeaseApp>().container
        val songs = (1..4).map { Song("vid$it", "Lagu $it", "Artis") }
        songs.forEach { c.library.setLiked(it, true) }
        val pid = c.library.createPlaylist("Jalan Pagi")
        c.library.addToPlaylist(pid, songs.take(3))
        val tree = LibraryTree(c)

        val root = tree.children(LibraryTree.ROOT)
        assertEquals(listOf("Baru diputar", "Lagu yang Disukai", "Playlist", "Unduhan", "Musik di HP"), root.map { it.mediaMetadata.title.toString() })
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true })

        val playlists = tree.children(LibraryTree.PLAYLISTS)
        assertEquals("Jalan Pagi", playlists.single().mediaMetadata.title.toString())
        val inPlaylist = tree.children(playlists.single().mediaId)
        assertEquals(3, inPlaylist.size)
        assertTrue(inPlaylist.all { it.mediaMetadata.isPlayable == true })

        // Pilih lagu ke-2 dari playlist → seluruh playlist masuk antrean mulai lagu itu.
        val (queue, index) = tree.expand(listOf(inPlaylist[1]))!!
        assertEquals(listOf("vid1", "vid2", "vid3"), queue.map { it.id })
        assertEquals(1, index)
        assertEquals("vid2", tree.realId(inPlaylist[1].mediaId))

        val liked = tree.children(LibraryTree.LIKED)
        assertEquals(4, liked.size)
        assertNull(tree.expand(listOf(com.freease.music.playback.MediaItems.fromSong(songs[0]))))
    }
}
