package com.freease.music.data

import com.freease.music.data.db.ArtistEntity
import com.freease.music.data.db.FreeaseDatabase
import com.freease.music.data.db.PlayEventEntity
import com.freease.music.data.db.PlaylistEntity
import com.freease.music.data.db.PlaylistSongEntity
import com.freease.music.data.db.SearchQueryEntity
import com.freease.music.data.db.SongEntity
import com.freease.music.data.model.ArtistItem
import com.freease.music.data.model.Song
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(private val db: FreeaseDatabase) {
    private val songs = db.songs()
    private val playlists = db.playlists()
    private val history = db.history()

    suspend fun save(song: Song) = songs.upsertMeta(SongEntity.from(song))
    suspend fun saveAll(list: List<Song>) = db.withTransaction { list.forEach { songs.upsertMeta(SongEntity.from(it)) } }

    fun observeSong(id: String): Flow<SongEntity?> = songs.observe(id)
    fun isLiked(id: String): Flow<Boolean> = songs.observe(id).map { it?.liked == true }
    suspend fun getSong(id: String) = songs.get(id)

    suspend fun setLiked(song: Song, liked: Boolean) {
        save(song)
        songs.setLiked(song.id, liked, System.currentTimeMillis())
    }

    suspend fun toggleLike(song: Song): Boolean {
        val now = !(songs.get(song.id)?.liked ?: false)
        setLiked(song, now)
        return now
    }

    fun liked() = songs.liked().map { l -> l.map { it.toSong() } }
    fun likedCount() = songs.likedCount()
    fun recent(limit: Int = 30) = songs.recent(limit).map { l -> l.map { it.toSong() } }
    fun mostPlayed(limit: Int = 20) = songs.mostPlayed(limit).map { l -> l.map { it.toSong() } }
    fun downloads() = songs.downloads()

    suspend fun recordPlay(song: Song, playedMs: Long) {
        val now = System.currentTimeMillis()
        save(song)
        songs.addPlay(song.id, playedMs, now)
        history.insert(PlayEventEntity(songId = song.id, timestamp = now, playedMs = playedMs))
    }

    suspend fun touch(song: Song) {
        save(song)
        songs.touch(song.id, System.currentTimeMillis())
    }

    suspend fun clearHistory() = db.withTransaction {
        history.clear()
        songs.clearPlayStats()
    }

    // ---------------------------------------------------------------- playlists

    fun playlistSummaries() = playlists.summaries()
    fun playlist(id: Long) = playlists.observe(id)
    fun playlistSongs(id: Long) = playlists.songs(id).map { rows -> rows.map { it.song.toSong() } }
    fun playlistsContaining(songId: String) = playlists.playlistsContaining(songId)

    suspend fun createPlaylist(name: String, description: String = "", remoteUrl: String? = null): Long =
        playlists.insert(PlaylistEntity(name = name.trim().ifEmpty { "Playlist baru" }, description = description, remoteUrl = remoteUrl))

    suspend fun renamePlaylist(id: Long, name: String, description: String) = playlists.rename(id, name, description)
    suspend fun deletePlaylist(id: Long) = db.withTransaction {
        playlists.clear(id)
        playlists.delete(id)
    }
    suspend fun setPinned(id: Long, pinned: Boolean) = playlists.setPinned(id, pinned)

    suspend fun addToPlaylist(playlistId: Long, list: List<Song>) = db.withTransaction {
        var pos = playlists.maxPosition(playlistId)
        list.forEach { s ->
            songs.upsertMeta(SongEntity.from(s))
            if (playlists.addSong(PlaylistSongEntity(playlistId = playlistId, songId = s.id, position = pos + 1)) != -1L) pos++
        }
        playlists.touch(playlistId)
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) {
        playlists.removeSong(playlistId, songId)
        playlists.touch(playlistId)
    }

    suspend fun moveInPlaylist(playlistId: Long, from: Int, to: Int) = db.withTransaction {
        val ids = playlists.songIds(playlistId).toMutableList()
        if (from !in ids.indices || to !in ids.indices) return@withTransaction
        ids.add(to, ids.removeAt(from))
        ids.forEachIndexed { i, id -> playlists.setPosition(playlistId, id, i) }
        playlists.touch(playlistId)
    }

    /** Simpan playlist/album YouTube sebagai salinan lokal. Kalau sudah ada, isinya diperbarui. */
    suspend fun importPlaylist(name: String, url: String, list: List<Song>): Long {
        val existing = playlists.byRemote(url)
        val id = existing?.id ?: createPlaylist(name, "Disimpan dari YouTube", url)
        addToPlaylist(id, list)
        return id
    }

    suspend fun remotePlaylistId(url: String): Long? = playlists.byRemote(url)?.id

    // ---------------------------------------------------------------- artists

    fun followedArtists() = db.artists().followed()
    fun isFollowing(url: String) = db.artists().isFollowed(url)
    suspend fun follow(a: ArtistItem) = db.artists().upsert(ArtistEntity(a.url, a.name, a.thumbnail))
    suspend fun unfollow(url: String) = db.artists().delete(url)

    // ---------------------------------------------------------------- search & stats

    fun recentSearches() = db.search().recent(12)
    suspend fun addSearch(q: String) = db.search().upsert(SearchQueryEntity(q.trim()))
    suspend fun deleteSearch(q: String) = db.search().delete(q)
    suspend fun clearSearches() = db.search().clear()

    fun historyRows(limit: Int = 300) = history.history(limit)
    fun topSongs(since: Long, limit: Int = 10) = history.topSongs(since, limit)
    fun topArtists(since: Long, limit: Int = 5) = history.topArtists(since, limit)
    fun totalMs(since: Long) = history.totalMs(since)
    fun distinctSongs(since: Long) = history.distinctSongs(since)
    fun perDay(since: Long, offset: Long) = history.perDay(since, offset)

    suspend fun searchLocal(q: String) = songs.searchLocal(q).map { it.toSong() }
}
