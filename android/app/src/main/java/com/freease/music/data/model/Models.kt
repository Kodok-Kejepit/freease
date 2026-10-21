package com.freease.music.data.model

import androidx.compose.runtime.Immutable

/** Satu lagu/video YouTube. `id` = video id YouTube. */
@Immutable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistUrl: String? = null,
    val album: String? = null,
    val durationSec: Long = 0,
    val thumbnail: String? = null,
    val isLive: Boolean = false,
) {
    /** Artwork resolusi tinggi untuk player, notifikasi, dan layar kunci. */
    val artworkUrl: String get() = Thumbs.best(id, thumbnail)

    /** Artwork kecil untuk daftar; lebih hemat data dan memori. */
    val artworkSmall: String get() = Thumbs.small(id, thumbnail)

    val shareUrl: String get() = "https://music.youtube.com/watch?v=$id"
}

@Immutable
data class PlaylistItem(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val count: Long = -1,
    val isAlbum: Boolean = false,
)

@Immutable
data class ArtistItem(
    val url: String,
    val name: String,
    val thumbnail: String?,
    val subscribers: Long = -1,
)

sealed interface BrowseResult {
    data class SongResult(val song: Song) : BrowseResult
    data class PlaylistResult(val playlist: PlaylistItem) : BrowseResult
    data class ArtistResult(val artist: ArtistItem) : BrowseResult
}

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

@Immutable
data class Lyrics(
    val synced: List<LyricLine>?,
    val plain: String?,
    val source: String = "LRCLIB",
) {
    val isEmpty: Boolean get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
}

object Thumbs {
    private val sizedGoogle = Regex("=w\\d+-h\\d+[^/]*$")
    private val sizedGoogleS = Regex("=s\\d+[^/]*$")

    private fun isGoogleArt(url: String?) =
        url != null && (url.contains("googleusercontent.com") || url.contains("ggpht.com"))

    /** Gambar YouTube Music (persegi) di-upscale; video YouTube pakai hq720 (16:9 tanpa bar hitam). */
    fun best(id: String, thumb: String?): String = when {
        isGoogleArt(thumb) -> upscale(thumb!!, 1080)
        else -> "https://i.ytimg.com/vi/$id/hq720.jpg"
    }

    fun small(id: String, thumb: String?): String = when {
        isGoogleArt(thumb) -> upscale(thumb!!, 226)
        else -> "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    fun fallback(id: String): String = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun upscale(url: String, size: Int): String = when {
        sizedGoogle.containsMatchIn(url) -> url.replace(sizedGoogle, "=w$size-h$size-l90-rj")
        sizedGoogleS.containsMatchIn(url) -> url.replace(sizedGoogleS, "=s$size")
        else -> url
    }
}
