package com.freease.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Query(
        "UPDATE songs SET title=:title, artist=:artist, artistUrl=COALESCE(:artistUrl, artistUrl), " +
            "album=COALESCE(:album, album), durationSec=CASE WHEN :durationSec > 0 THEN :durationSec ELSE durationSec END, " +
            "thumbnail=COALESCE(:thumbnail, thumbnail), isLive=:isLive WHERE id=:id",
    )
    suspend fun updateMeta(
        id: String, title: String, artist: String, artistUrl: String?, album: String?,
        durationSec: Long, thumbnail: String?, isLive: Boolean,
    )

    @Transaction
    suspend fun upsertMeta(e: SongEntity) {
        if (insertIgnore(e) == -1L) {
            updateMeta(e.id, e.title, e.artist, e.artistUrl, e.album, e.durationSec, e.thumbnail, e.isLive)
        }
    }

    @Query("SELECT * FROM songs WHERE id=:id")
    suspend fun get(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id=:id")
    fun observe(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<SongEntity>

    @Query("UPDATE songs SET liked=:liked, likedAt=:at WHERE id=:id")
    suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Query("SELECT * FROM songs WHERE liked=1 ORDER BY likedAt DESC")
    fun liked(): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs WHERE liked=1")
    fun likedCount(): Flow<Int>

    @Query("SELECT * FROM songs WHERE liked=1 ORDER BY likedAt DESC LIMIT :limit")
    suspend fun likedNow(limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs WHERE lastPlayedAt>0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playCount>0 ORDER BY playCount DESC, totalPlayMs DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE downloadState!=0 ORDER BY addedAt DESC")
    fun downloads(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET downloadState=:state, downloadProgress=:progress WHERE id=:id")
    suspend fun setDownloadState(id: String, state: Int, progress: Float)

    @Query("UPDATE songs SET downloadState=:state, downloadProgress=1, downloadPath=:path, downloadBytes=:bytes WHERE id=:id")
    suspend fun setDownloaded(id: String, state: Int, path: String?, bytes: Long)

    @Query(
        "UPDATE songs SET playCount=playCount+1, totalPlayMs=totalPlayMs+:ms, lastPlayedAt=:at WHERE id=:id",
    )
    suspend fun addPlay(id: String, ms: Long, at: Long)

    @Query("UPDATE songs SET lastPlayedAt=:at WHERE id=:id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE songs SET lastPlayedAt=0, playCount=0, totalPlayMs=0")
    suspend fun clearPlayStats()

    @Query("SELECT * FROM songs WHERE liked=1 AND likedAt >= :from AND likedAt < :to ORDER BY likedAt DESC")
    suspend fun likedBetween(from: Long, to: Long): List<SongEntity>

    @Query(
        """
        SELECT artist AS artist, COUNT(*) AS likes, MAX(id) AS sampleId, MAX(thumbnail) AS sampleThumb FROM songs
        WHERE liked=1 AND likedAt >= :from AND likedAt < :to GROUP BY artist ORDER BY likes DESC, artist LIMIT :limit
        """,
    )
    suspend fun likedArtistsBetween(from: Long, to: Long, limit: Int): List<LikedArtistStat>

    @Query("SELECT * FROM songs WHERE downloadState=3")
    suspend fun downloadedNow(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE downloadState=0 AND (liked=1 OR playCount>=2) AND isLive=0")
    suspend fun downloadCandidates(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' LIMIT 30")
    suspend fun searchLocal(q: String): List<SongEntity>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name=:name, description=:desc, updatedAt=:at WHERE id=:id")
    suspend fun rename(id: Long, name: String, desc: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET pinned=:pinned WHERE id=:id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE playlists SET updatedAt=:at WHERE id=:id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId=:id")
    suspend fun clear(id: Long)

    @Query("SELECT * FROM playlists WHERE id=:id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE remoteUrl=:url LIMIT 1")
    suspend fun byRemote(url: String): PlaylistEntity?

    @Query(
        """
        SELECT p.id, p.name, p.description, p.remoteUrl, p.pinned, p.updatedAt,
          (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
          (SELECT GROUP_CONCAT(songId) FROM (SELECT ps2.songId AS songId FROM playlist_songs ps2
              WHERE ps2.playlistId = p.id ORDER BY ps2.position LIMIT 4)) AS coverIds
        FROM playlists p ORDER BY p.pinned DESC, p.updatedAt DESC
        """,
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT s.*, ps.position AS position FROM playlist_songs ps
        JOIN songs s ON s.id = ps.songId WHERE ps.playlistId = :id ORDER BY ps.position
        """,
    )
    fun songs(id: Long): Flow<List<PlaylistSongRow>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId=:id")
    suspend fun maxPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(e: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId=:playlistId AND songId=:songId")
    suspend fun removeSong(playlistId: Long, songId: String)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId=:id ORDER BY position")
    suspend fun songIds(id: Long): List<String>

    @Query("UPDATE playlist_songs SET position=:position WHERE playlistId=:playlistId AND songId=:songId")
    suspend fun setPosition(playlistId: Long, songId: String, position: Int)

    @Query("SELECT playlistId FROM playlist_songs WHERE songId=:songId")
    fun playlistsContaining(songId: String): Flow<List<Long>>
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(e: PlayEventEntity)

    @Query(
        """
        SELECT s.*, e.timestamp AS playedAt FROM play_events e JOIN songs s ON s.id = e.songId
        ORDER BY e.timestamp DESC LIMIT :limit
        """,
    )
    fun history(limit: Int): Flow<List<HistoryRow>>

    @Query(
        """
        SELECT s.*, SUM(e.playedMs) AS ms, COUNT(e.id) AS plays FROM play_events e
        JOIN songs s ON s.id = e.songId WHERE e.timestamp >= :since
        GROUP BY s.id ORDER BY ms DESC LIMIT :limit
        """,
    )
    fun topSongs(since: Long, limit: Int): Flow<List<SongStat>>

    @Query(
        """
        SELECT s.artist AS artist, MAX(s.artistUrl) AS artistUrl, MAX(s.id) AS sampleId,
          MAX(s.thumbnail) AS sampleThumb, SUM(e.playedMs) AS ms, COUNT(e.id) AS plays
        FROM play_events e JOIN songs s ON s.id = e.songId WHERE e.timestamp >= :since
        GROUP BY s.artist ORDER BY ms DESC LIMIT :limit
        """,
    )
    fun topArtists(since: Long, limit: Int): Flow<List<ArtistStat>>

    @Query("SELECT COALESCE(SUM(playedMs), 0) FROM play_events WHERE timestamp >= :since")
    fun totalMs(since: Long): Flow<Long>

    @Query("SELECT COUNT(DISTINCT songId) FROM play_events WHERE timestamp >= :since")
    fun distinctSongs(since: Long): Flow<Int>

    @Query(
        """
        SELECT ((timestamp + :offset) / 86400000) AS day, SUM(playedMs) AS ms FROM play_events
        WHERE timestamp >= :since GROUP BY day ORDER BY day
        """,
    )
    fun perDay(since: Long, offset: Long): Flow<List<DayStat>>

    /** Bahan belajar Freease Pintar: riwayat dengar terbaru beserta durasi lagunya. */
    @Query(
        """
        SELECT e.songId AS songId, s.artist AS artist, s.title AS title, e.timestamp AS ts,
          e.playedMs AS playedMs, s.durationSec AS durationSec
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.timestamp >= :since ORDER BY e.timestamp DESC LIMIT :limit
        """,
    )
    suspend fun tasteEvents(since: Long, limit: Int): List<TasteEvent>

    @Query(
        """
        SELECT s.*, SUM(e.playedMs) AS ms, COUNT(e.id) AS plays FROM play_events e
        JOIN songs s ON s.id = e.songId WHERE e.timestamp >= :from AND e.timestamp < :to
        GROUP BY s.id ORDER BY plays DESC, ms DESC LIMIT :limit
        """,
    )
    suspend fun topSongsBetween(from: Long, to: Long, limit: Int): List<SongStat>

    @Query(
        """
        SELECT s.album AS album, MAX(s.artist) AS artist, MAX(s.id) AS sampleId, MAX(s.thumbnail) AS sampleThumb,
          SUM(e.playedMs) AS ms, COUNT(e.id) AS plays, COUNT(DISTINCT s.id) AS tracks
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.timestamp >= :from AND e.timestamp < :to AND s.album IS NOT NULL AND s.album != ''
        GROUP BY s.album, s.artist ORDER BY plays DESC, ms DESC LIMIT :limit
        """,
    )
    suspend fun topAlbumsBetween(from: Long, to: Long, limit: Int): List<AlbumStat>

    @Query(
        """
        SELECT s.artist AS artist, MAX(s.artistUrl) AS artistUrl, MAX(s.id) AS sampleId,
          MAX(s.thumbnail) AS sampleThumb, SUM(e.playedMs) AS ms, COUNT(e.id) AS plays
        FROM play_events e JOIN songs s ON s.id = e.songId WHERE e.timestamp >= :from AND e.timestamp < :to
        GROUP BY s.artist ORDER BY ms DESC LIMIT :limit
        """,
    )
    suspend fun topArtistsBetween(from: Long, to: Long, limit: Int): List<ArtistStat>

    @Query("SELECT timestamp AS ts, playedMs AS ms FROM play_events WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun stampsBetween(from: Long, to: Long): List<PlayStamp>

    @Query("SELECT COUNT(DISTINCT e.songId) FROM play_events e WHERE e.timestamp >= :from AND e.timestamp < :to")
    suspend fun distinctSongsBetween(from: Long, to: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT s.artist) FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.timestamp >= :from AND e.timestamp < :to
        """,
    )
    suspend fun distinctArtistsBetween(from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(playedMs), 0) FROM play_events WHERE timestamp >= :from AND timestamp < :to")
    suspend fun totalMsBetween(from: Long, to: Long): Long

    @Query("SELECT COALESCE(MIN(timestamp), 0) FROM play_events")
    suspend fun firstEventAt(): Long

    @Query("DELETE FROM play_events")
    suspend fun clear()
}

@Dao
interface SearchDao {
    @Upsert
    suspend fun upsert(e: SearchQueryEntity)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchQueryEntity>>

    @Query("DELETE FROM search_history WHERE `query`=:q")
    suspend fun delete(q: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface ArtistDao {
    @Upsert
    suspend fun upsert(e: ArtistEntity)

    @Query("DELETE FROM artists WHERE url=:url")
    suspend fun delete(url: String)

    @Query("SELECT * FROM artists ORDER BY followedAt DESC")
    fun followed(): Flow<List<ArtistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM artists WHERE url=:url)")
    fun isFollowed(url: String): Flow<Boolean>
}
