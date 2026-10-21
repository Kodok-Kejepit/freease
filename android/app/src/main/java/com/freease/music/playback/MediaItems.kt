package com.freease.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.freease.music.data.model.Song
import com.freease.music.data.prefs.AudioQuality
import java.io.File

/**
 * Skema URI internal "freease://<jenis>/<videoId>?q=..&h=..". URL googlevideo asli baru
 * diambil saat data benar-benar dibaca (lihat [StreamResolver]), jadi antrian panjang
 * tidak memicu ratusan ekstraksi sekaligus dan URL yang kedaluwarsa bisa diambil ulang.
 */
object MediaItems {
    const val SCHEME = "freease"
    const val HOST_AUDIO = "audio"
    const val HOST_VIDEO = "video"
    const val HOST_VIDEO_ONLY = "videoonly"
    const val HOST_LIVE = "live"

    private const val EXTRA_ARTIST_URL = "artistUrl"
    private const val EXTRA_THUMB = "thumb"
    private const val EXTRA_DURATION = "duration"
    private const val EXTRA_LIVE = "live"

    fun fromSong(song: Song): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_ARTIST_URL, song.artistUrl)
            putString(EXTRA_THUMB, song.thumbnail)
            putLong(EXTRA_DURATION, song.durationSec)
            putBoolean(EXTRA_LIVE, song.isLive)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.artworkUrl.toUri())
            .setDurationMs(if (song.durationSec > 0) song.durationSec * 1000 else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri("$SCHEME://$HOST_AUDIO/${song.id}")
            .setMediaMetadata(metadata)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri("$SCHEME://$HOST_AUDIO/${song.id}".toUri()).build(),
            )
            .build()
    }

    fun toSong(item: MediaItem): Song {
        val m = item.mediaMetadata
        val e = m.extras
        return Song(
            id = item.mediaId,
            title = m.title?.toString() ?: "",
            artist = m.artist?.toString() ?: "",
            artistUrl = e?.getString(EXTRA_ARTIST_URL),
            album = m.albumTitle?.toString(),
            durationSec = e?.getLong(EXTRA_DURATION) ?: 0,
            thumbnail = e?.getString(EXTRA_THUMB),
            isLive = e?.getBoolean(EXTRA_LIVE) == true,
        )
    }

    fun isLive(item: MediaItem) = item.mediaMetadata.extras?.getBoolean(EXTRA_LIVE) == true

    fun isVideo(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return false
        return uri.scheme == SCHEME && (uri.host == HOST_VIDEO || uri.host == HOST_LIVE)
    }

    /** Pasang URI yang bisa diputar oleh player di service. */
    fun forPlayback(
        item: MediaItem,
        video: Boolean,
        localPath: String?,
        quality: AudioQuality,
        videoHeight: Int,
    ): MediaItem {
        val id = item.mediaId
        val b = item.buildUpon()
        when {
            isLive(item) -> b.setUri("$SCHEME://$HOST_LIVE/$id").setCustomCacheKey(null)
            video -> b.setUri("$SCHEME://$HOST_VIDEO/$id?q=${quality.name}&h=$videoHeight")
                .setCustomCacheKey(audioKey(id, quality))
            localPath != null && File(localPath).exists() -> b.setUri(Uri.fromFile(File(localPath))).setCustomCacheKey(null)
            else -> b.setUri(audioUri(id, quality)).setCustomCacheKey(audioKey(id, quality))
        }
        return b.build()
    }

    fun audioUri(id: String, quality: AudioQuality) = "$SCHEME://$HOST_AUDIO/$id?q=${quality.name}"
    fun audioKey(id: String, quality: AudioQuality) = "a/$id/${quality.name}"
    fun videoUri(id: String, height: Int) = "$SCHEME://$HOST_VIDEO_ONLY/$id?h=$height"
    fun videoKey(id: String, height: Int) = "v/$id/$height"
}
