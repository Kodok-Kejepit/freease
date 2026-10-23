package com.freease.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.freease.music.data.model.Song

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val RUNNING = 2
    const val DONE = 3
    const val FAILED = 4
}

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistUrl: String? = null,
    val album: String? = null,
    val durationSec: Long = 0,
    val thumbnail: String? = null,
    val isLive: Boolean = false,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val playCount: Int = 0,
    val totalPlayMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val downloadState: Int = DownloadState.NONE,
    val downloadProgress: Float = 0f,
    val downloadPath: String? = null,
    val downloadBytes: Long = 0,
) {
    fun toSong() = Song(id, title, artist, artistUrl, album, durationSec, thumbnail, isLive)

    companion object {
        fun from(s: Song) = SongEntity(
            id = s.id, title = s.title, artist = s.artist, artistUrl = s.artistUrl,
            album = s.album, durationSec = s.durationSec, thumbnail = s.thumbnail, isLive = s.isLive,
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteUrl: String? = null,
    val pinned: Boolean = false,
)

@Entity(
    tableName = "playlist_songs",
    indices = [Index(value = ["playlistId", "songId"], unique = true), Index("songId")],
)
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "play_events", indices = [Index("songId"), Index("timestamp")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val timestamp: Long,
    val playedMs: Long,
)

@Entity(tableName = "search_history")
data class SearchQueryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val url: String,
    val name: String,
    val thumbnail: String?,
    val followedAt: Long = System.currentTimeMillis(),
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val description: String,
    val remoteUrl: String?,
    val pinned: Boolean,
    val updatedAt: Long,
    val songCount: Int,
    val coverIds: String?,
) {
    val covers: List<String> get() = coverIds?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}

data class PlaylistSongRow(
    @Embedded val song: SongEntity,
    @ColumnInfo(name = "position") val position: Int,
)

data class SongStat(
    @Embedded val song: SongEntity,
    val ms: Long,
    val plays: Int,
)

data class ArtistStat(
    val artist: String,
    val artistUrl: String?,
    val sampleId: String,
    val sampleThumb: String?,
    val ms: Long,
    val plays: Int,
)

data class DayStat(val day: Long, val ms: Long)

data class HistoryRow(
    @Embedded val song: SongEntity,
    val playedAt: Long,
)

/** Satu kejadian dengar, dipakai untuk menghitung selera (bukan entitas tabel). */
data class TasteEvent(
    val songId: String,
    val artist: String,
    val title: String,
    val ts: Long,
    val playedMs: Long,
    val durationSec: Long,
)

/** Album yang paling sering diputar dalam satu rentang waktu. */
data class AlbumStat(
    val album: String,
    val artist: String,
    val sampleId: String,
    val sampleThumb: String?,
    val ms: Long,
    val plays: Int,
    val tracks: Int,
)

/** Cap waktu satu kejadian dengar (bahan analisis jam, hari, dan runtunan). */
data class PlayStamp(val ts: Long, val ms: Long)

/** Artis yang paling banyak lagunya disukai dalam satu rentang waktu. */
data class LikedArtistStat(val artist: String, val likes: Int, val sampleId: String, val sampleThumb: String?)
