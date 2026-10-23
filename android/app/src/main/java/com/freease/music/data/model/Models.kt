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
    /** Terisi kalau lagu ini sisipan Freease Pintar; isinya alasan singkat kenapa dipilih. */
    val smartWhy: String? = null,
) {
    /** Berkas musik dari penyimpanan HP (bukan YouTube). */
    val isLocal: Boolean get() = id.startsWith("local:")

    val isSmartPick: Boolean get() = smartWhy != null

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

/** Satu kata dengan waktu mulainya; [start] = posisi karakter kata itu di teks baris. */
@Immutable
data class LyricWord(val timeMs: Long, val start: Int, val end: Int)

/** [words] diisi kalau sumber lirik memberi waktu per kata (LRC "enhanced"). */
@Immutable
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord>? = null)

@Immutable
data class Lyrics(
    val synced: List<LyricLine>?,
    val plain: String?,
    val source: String = "",
    /** Geseran sinkron: positif = lirik muncul lebih lambat. Disimpan per video. */
    val offsetMs: Long = 0,
    /**
     * false = waktu lirik dibuat untuk versi lain lagu ini (mis. video klip dengan intro berbeda),
     * jadi baris belum boleh mengikuti lagu sampai pengguna menyelaraskannya.
     */
    val timingTrusted: Boolean = true,
    /** Pengguna sudah menyelaraskan sendiri (ketuk baris yang sedang dinyanyikan / geser manual). */
    val userSynced: Boolean = false,
    /** Video id rekaman yang menjadi acuan waktu lirik ini (versi lagu resmi), kalau beda dengan yang diputar. */
    val timingRefId: String? = null,
    /** Status penyelarasan otomatis berbasis suara: lihat [AutoSync]. */
    val autoSync: Int = AutoSync.NONE,
    /**
     * Waktunya belum dijamin pas, tapi durasi rekamannya hampir sama dengan video ini (selisih beberapa
     * detik), jadi lirik tetap boleh berjalan sambil menunggu penyelarasan yang tepat.
     */
    val approxTiming: Boolean = false,
) {
    object AutoSync {
        const val NONE = 0
        const val DONE = 1
        const val NOT_MATCHING = 2
    }

    /** Waktunya belum cocok dengan video ini, tapi bisa diselaraskan otomatis dari rekaman acuannya. */
    val canAutoSync: Boolean
        get() = !synced.isNullOrEmpty() && !timingTrusted && !userSynced && timingRefId != null && autoSync == AutoSync.NONE

    val isEmpty: Boolean get() = synced.isNullOrEmpty() && plain.isNullOrBlank()

    /** Baris boleh disorot dan digulir mengikuti lagu. */
    val canFollow: Boolean get() = !synced.isNullOrEmpty() && (timingTrusted || userSynced || approxTiming)

    /** Waktunya dijamin pas (bukan sekadar perkiraan). */
    val exactTiming: Boolean get() = !synced.isNullOrEmpty() && (timingTrusted || userSynced)

    /** Teks lengkap untuk tampilan tanpa sinkron. */
    val text: String get() = plain?.takeIf { it.isNotBlank() } ?: synced.orEmpty().joinToString("\n") { it.text }
}

object Thumbs {
    private val sizedGoogle = Regex("=w\\d+-h\\d+[^/]*$")
    private val sizedGoogleS = Regex("=s\\d+[^/]*$")

    private fun isGoogleArt(url: String?) =
        url != null && (url.contains("googleusercontent.com") || url.contains("ggpht.com"))

    /** Gambar YouTube Music (persegi) di-upscale; video YouTube pakai hq720 (16:9 tanpa bar hitam). */
    fun best(id: String, thumb: String?): String = when {
        id.startsWith("local:") -> thumb ?: "localart://${id.removePrefix("local:")}"
        isGoogleArt(thumb) -> upscale(thumb!!, 1080)
        else -> "https://i.ytimg.com/vi/$id/hq720.jpg"
    }

    fun small(id: String, thumb: String?): String = when {
        id.startsWith("local:") -> thumb ?: "localart://${id.removePrefix("local:")}"
        isGoogleArt(thumb) -> upscale(thumb!!, 226)
        else -> "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    fun fallback(id: String): String =
        if (id.startsWith("local:")) "localart://${id.removePrefix("local:")}" else "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun upscale(url: String, size: Int): String = when {
        sizedGoogle.containsMatchIn(url) -> url.replace(sizedGoogle, "=w$size-h$size-l90-rj")
        sizedGoogleS.containsMatchIn(url) -> url.replace(sizedGoogleS, "=s$size")
        else -> url
    }
}
