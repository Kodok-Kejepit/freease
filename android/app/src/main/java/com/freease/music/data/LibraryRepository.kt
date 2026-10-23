package com.freease.music.data

import com.freease.music.data.db.ArtistEntity
import com.freease.music.data.db.FreeaseDatabase
import com.freease.music.data.db.PlayEventEntity
import com.freease.music.data.db.PlaylistEntity
import com.freease.music.data.db.PlaylistSongEntity
import com.freease.music.data.db.SearchQueryEntity
import com.freease.music.data.db.SongEntity
import com.freease.music.data.model.ArtistItem
import com.freease.music.data.backup.BackupArtist
import com.freease.music.data.backup.BackupData
import com.freease.music.data.backup.BackupPlaylist
import com.freease.music.data.backup.BackupSummary
import com.freease.music.data.model.Song
import com.freease.music.data.monthly.MonthKey
import com.freease.music.data.monthly.MonthlyAchievements
import com.freease.music.data.monthly.MonthlyFacts
import com.freease.music.data.monthly.MonthlyInsights
import com.freease.music.data.monthly.MonthlyReport
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    suspend fun tasteEvents(since: Long, limit: Int = 1500) = history.tasteEvents(since, limit)
    suspend fun likedSongs(limit: Int = 400) = songs.likedNow(limit).map { it.toSong() }

    fun historyRows(limit: Int = 300) = history.history(limit)
    fun topSongs(since: Long, limit: Int = 10) = history.topSongs(since, limit)
    fun topArtists(since: Long, limit: Int = 5) = history.topArtists(since, limit)
    fun totalMs(since: Long) = history.totalMs(since)
    fun distinctSongs(since: Long) = history.distinctSongs(since)
    fun perDay(since: Long, offset: Long) = history.perDay(since, offset)

    suspend fun searchLocal(q: String) = songs.searchLocal(q).map { it.toSong() }

    // ---------------------------------------------------------------- Monthly Freease

    /** Bulan paling awal yang punya data dengar (atau bulan ini kalau belum ada). */
    suspend fun firstMonth(): MonthKey {
        val first = history.firstEventAt()
        return MonthKey.of(if (first > 0) first else System.currentTimeMillis())
    }

    suspend fun monthlyReport(month: MonthKey): MonthlyReport {
        val from = month.start()
        val to = month.end()
        val stamps = history.stampsBetween(from, to)
        val insights = MonthlyInsights.compute(stamps, month)
        val topSongs = history.topSongsBetween(from, to, 5)
        val topArtists = history.topArtistsBetween(from, to, 5)
        val liked = songs.likedBetween(from, to)
        val facts = MonthlyFacts(
            insights = insights,
            distinctSongs = history.distinctSongsBetween(from, to),
            distinctArtists = history.distinctArtistsBetween(from, to),
            topSongPlays = topSongs.firstOrNull()?.plays ?: 0,
            topArtistShare = topArtists.firstOrNull()?.let {
                val total = history.totalMsBetween(from, to)
                if (total > 0) it.ms.toDouble() / total else 0.0
            } ?: 0.0,
            likes = liked.size,
        )
        val prevMs = history.totalMsBetween(month.prev().start(), from)
        val nowMs = insights.totalMin * 60_000L
        return MonthlyReport(
            month = month,
            insights = insights,
            persona = MonthlyAchievements.persona(facts),
            achievements = MonthlyAchievements.evaluate(facts),
            distinctSongs = facts.distinctSongs,
            distinctArtists = facts.distinctArtists,
            topSongs = topSongs,
            topAlbums = history.topAlbumsBetween(from, to, 5),
            topArtists = topArtists,
            likedSongs = liked.map { it.toSong() },
            likedArtists = songs.likedArtistsBetween(from, to, 3),
            deltaPercent = if (prevMs >= 10 * 60_000L) (((nowMs - prevMs) * 100.0) / prevMs).toInt() else null,
        )
    }

    // ---------------------------------------------------------------- cadangan

    suspend fun exportBackup(): BackupData {
        val lists = playlists.summaries().first().map { p ->
            BackupPlaylist(p.name, p.description, p.pinned, playlists.songs(p.id).first().map { it.song.toSong() })
        }
        return BackupData(
            playlists = lists,
            liked = songs.likedNow(100_000).map { it.toSong() },
            artists = db.artists().followed().first().map { BackupArtist(it.url, it.name, it.thumbnail) },
        )
    }

    /** Menggabungkan cadangan ke koleksi sekarang tanpa menghapus apa pun dan tanpa duplikat. */
    suspend fun importBackup(data: BackupData): BackupSummary = db.withTransaction {
        var newLiked = 0
        for (song in data.liked) {
            if (songs.get(song.id)?.liked != true) {
                songs.upsertMeta(SongEntity.from(song))
                songs.setLiked(song.id, true, System.currentTimeMillis())
                newLiked++
            }
        }
        val existing = playlists.summaries().first().filter { it.remoteUrl == null }.associateBy { it.name.trim().lowercase() }
        var touched = 0
        var added = 0
        for (p in data.playlists) {
            val before = existing[p.name.trim().lowercase()]
            val id = before?.id ?: playlists.insert(PlaylistEntity(name = p.name.trim().ifEmpty { "Playlist" }, description = p.description, pinned = p.pinned))
            val have = playlists.songIds(id).toHashSet()
            val fresh = p.songs.filter { it.id !in have }.distinctBy { it.id }
            var pos = playlists.maxPosition(id)
            for (s in fresh) {
                songs.upsertMeta(SongEntity.from(s))
                if (playlists.addSong(PlaylistSongEntity(playlistId = id, songId = s.id, position = pos + 1)) != -1L) { pos++; added++ }
            }
            playlists.touch(id)
            touched++
        }
        var artists = 0
        val followed = db.artists().followed().first().map { it.url }.toHashSet()
        for (a in data.artists) {
            if (a.url !in followed) {
                db.artists().upsert(ArtistEntity(a.url, a.name, a.thumbnail))
                artists++
            }
        }
        BackupSummary(newLiked, touched, added, artists)
    }
}
