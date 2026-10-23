package com.freease.music.playback

import android.content.Context
import com.freease.music.data.lyrics.LyricsMatch
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.YouTubeRepository

/** Potongan video untuk Canvas: [videoId] diputar dari [startMs] selama [lengthMs], berulang. */
data class CanvasClip(val videoId: String, val startMs: Long, val lengthMs: Long = 8_000)

/**
 * Mencari video untuk Canvas. Video klip dipakai langsung; versi audio resmi ("Topic", videonya
 * hanya sampul diam) dicarikan video klip resminya. Hasil (juga "tidak ada") diingat per lagu.
 */
class CanvasResolver(context: Context, private val youtube: YouTubeRepository) {
    private val prefs = context.getSharedPreferences("canvas", Context.MODE_PRIVATE)

    suspend fun resolve(song: Song): CanvasClip? {
        if (song.isLocal || song.isLive) return null
        prefs.getString(song.id, null)?.let { saved ->
            if (saved.isEmpty()) return null
            val (id, start) = saved.split('@').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 30_000L) }
            return CanvasClip(id, start)
        }
        val clip = runCatching { find(song) }.getOrElse { return null } // gagal jaringan: coba lagi lain kali
        prefs.edit().putString(song.id, clip?.let { "${it.videoId}@${it.startMs}" } ?: "").apply()
        return clip
    }

    private suspend fun find(song: Song): CanvasClip? {
        val info = youtube.streamInfo(song.id)
        val videoId = if (!info.isAudioTrack && info.videoOnlyStreams.isNotEmpty()) {
            song.id
        } else {
            val candidates = youtube.searchMusicVideos("${song.artist} ${song.title}", 10)
            // Hanya video dari kanal artisnya sendiri (atau VEVO-nya) — bukan unggahan penggemar.
            pickVideos(song, candidates).take(3).firstOrNull { c ->
                val ci = runCatching { youtube.streamInfo(c.id) }.getOrNull() ?: return@firstOrNull false
                !ci.isAudioTrack && ci.videoOnlyStreams.isNotEmpty() && officialChannel(ci.uploaderName, song.artist)
            }?.id ?: return null
        }
        val dur = if (videoId == song.id) info.song.durationSec else youtube.streamInfo(videoId).song.durationSec
        return CanvasClip(videoId, startFor(dur))
    }

    companion object {
        /** Video klip yang judul & artisnya cocok dan durasinya masuk akal (boleh ada intro/outro). */
        fun pickVideo(song: Song, candidates: List<Song>): Song? = pickVideos(song, candidates).firstOrNull()

        fun pickVideos(song: Song, candidates: List<Song>): List<Song> = candidates.filter { c ->
            c.id != song.id &&
                !alteredVersion(c.title, song.title) &&
                LyricsMatch.songMatches(c.title, c.artist, song.title, song.artist) &&
                LyricsMatch.songMatches(song.title, song.artist, c.title, c.artist) &&
                (song.durationSec <= 0 || c.durationSec in (song.durationSec - 15)..(song.durationSec + 240))
        }

        private fun norm(s: String) = s.lowercase()
            .replace(Regex("\\b(vevo|official|- topic|topic|music|tv|channel)\\b"), "")
            .filter { it.isLetterOrDigit() }

        /** Kanal pengunggah = artis lagu ini (mis. "TulusVEVO", "Tulus Official" untuk "Tulus"). */
        fun officialChannel(uploader: String?, artist: String): Boolean {
            val u = norm(uploader ?: return false)
            if (u.length < 2) return false
            return artist.split(Regex(",|&| x | feat\\.? | ft\\.? ", RegexOption.IGNORE_CASE))
                .map(::norm).filter { it.length >= 2 }
                .any { a -> u == a || (a.length >= 4 && u.contains(a)) || (u.length >= 4 && a.contains(u)) }
        }

        private val ALTERED = Regex(
            "\\b(remix|instrumental|live|cover|karaoke|sped ?up|slowed|reverb|nightcore|8d|lyrics?|lirik|reaction|mashup|acoustic|piano|fanmade|fan made|shorts|mv reaction|dance practice|tutorial|perform(ance)?|teaser|sub espa[nñ]ol)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Versi lain (remix, live, cover, lirik, …) yang tidak disebut di judul lagu aslinya. */
        private fun alteredVersion(candidate: String, original: String): Boolean {
            val found = ALTERED.findAll(candidate).map { it.value.lowercase() }.toSet()
            if (found.isEmpty()) return false
            val inOriginal = ALTERED.findAll(original).map { it.value.lowercase() }.toSet()
            return (found - inOriginal).isNotEmpty()
        }

        /** Mulai sekitar sepertiga lagu — biasanya sudah masuk bagian yang paling "hidup". */
        fun startFor(durationSec: Long): Long {
            if (durationSec <= 0) return 30_000
            return (durationSec * 1000 / 3).coerceIn(10_000, (durationSec * 1000 - 12_000).coerceAtLeast(0))
        }
    }
}
