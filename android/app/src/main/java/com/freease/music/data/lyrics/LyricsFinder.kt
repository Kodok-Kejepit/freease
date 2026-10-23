package com.freease.music.data.lyrics

import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.data.model.TitleCleaner
import java.io.IOException

/**
 * Mencari lirik terbaik untuk satu video, berurutan:
 * 1. Lirik YouTube Music milik video ini sendiri. Kalau bertanda waktu, waktunya pasti pas.
 * 2. Video klip / unggahan lain: cari versi audio resminya di YouTube Music dan pakai liriknya.
 *    Waktunya diikuti otomatis hanya kalau durasi keduanya sama.
 * 3. LRCLIB sebagai cadangan, dengan artis wajib cocok dan versi akustik/live/cover ditolak.
 * 4. Teks tanpa waktu kalau hanya itu yang ada.
 *
 * Melempar [IOException] kalau tidak ketemu DAN ada sumber yang gagal karena jaringan, supaya
 * hasil "tidak ada lirik" tidak disimpan gara-gara koneksi sedang buruk.
 */
class LyricsFinder(private val ytm: MusicLyricsSource, private val lrc: LrcSource) {

    fun find(song: Song): Lyrics {
        var netError: IOException? = null
        fun <T> net(block: () -> T): T? = try {
            block()
        } catch (e: HttpStatusException) {
            if (!e.isClientError) netError = e
            null
        } catch (e: IOException) {
            netError = e
            null
        }

        val dur = song.durationSec
        val title = TitleCleaner.cleanTitle(song.title)
        val artist = TitleCleaner.cleanArtist(song.artist)

        // 1. Lirik video ini sendiri.
        val info = if (song.isLocal) null else net { ytm.watchInfo(song.id) }
        val own = info?.lyricsId?.let { id -> net { ytm.lyrics(id) } }
        if (own != null && own.timed) {
            return Lyrics(own.lines, null, own.source ?: SOURCE_YTM, timingTrusted = true)
        }
        // Berkas musik di HP hampir selalu versi album/audio resmi.
        val isAudio = info?.isAudioTrack == true || song.isLocal

        // 2. Bukan versi audio resmi: pinjam lirik bertanda waktu dari versi audionya.
        var ref: MusicTrack? = null
        if (!isAudio && title.isNotBlank()) {
            ref = LyricsMatch.bestTrack(net { ytm.searchSongs("$artist $title") }.orEmpty(), title, artist)
                ?.takeIf { !LyricsMatch.isCompilation(dur, it.durationSec) }
            val refLyrics = ref?.let { r ->
                net { ytm.watchInfo(r.videoId) }?.lyricsId?.let { id -> net { ytm.lyrics(id) } }
            }
            if (ref != null && refLyrics != null && refLyrics.timed) {
                val sameRecording = LyricsMatch.sameCut(dur, ref.durationSec) && !LyricsMatch.isOtherVersion(song.title, ref.title)
                return Lyrics(
                    refLyrics.lines, null, refLyrics.source ?: SOURCE_YTM,
                    timingTrusted = sameRecording,
                    // Waktunya milik versi lagu: bisa diselaraskan otomatis ke video ini lewat suaranya.
                    timingRefId = if (sameRecording) null else ref.videoId,
                    approxTiming = !sameRecording && LyricsMatch.nearCut(dur, ref.durationSec) &&
                        !LyricsMatch.isOtherVersion(song.title, ref.title),
                )
            }
        }

        // 3. LRCLIB (juga dicoba dengan judul/artis ditukar untuk unggahan "Judul - Artis").
        var best: LyricsMatch.LrcPick? = null
        if (title.isNotBlank() && artist.isNotBlank()) {
            for ((t, a) in listOf(title to artist, artist to title)) {
                val entries = LinkedHashMap<Long, LrcEntry>()
                net { lrc.search(t, a) }?.forEach { entries.putIfAbsent(it.id, it) }
                if (LyricsMatch.pickLrc(entries.values.toList(), t, a, dur)?.entry?.synced == null) {
                    net { lrc.searchQuery("$a $t") }?.forEach { entries.putIfAbsent(it.id, it) }
                }
                val p = LyricsMatch.pickLrc(entries.values.toList(), t, a, dur)
                if (p != null && (best == null || (best.entry.synced == null && p.entry.synced != null))) best = p
                if (best?.entry?.synced != null) break
            }
        }
        // Video kompilasi / full album tidak boleh meminjam lirik satu lagu.
        val pick = best?.takeIf { !LyricsMatch.isCompilation(dur, LyricsMatch.lrcSongSec(it)) }
        if (pick?.entry?.synced != null) {
            val trusted = LyricsMatch.trustLrc(pick, dur, isAudio, ref?.durationSec) &&
                !LyricsMatch.isOtherVersion(song.title, pick.entry.title)
            val refId = ref?.takeIf { !trusted && LyricsMatch.lrcFitsRecording(pick, it.durationSec) }?.videoId
            val approx = !trusted && LyricsMatch.nearCut(dur, LyricsMatch.lrcSongSec(pick)) &&
                !LyricsMatch.isOtherVersion(song.title, pick.entry.title)
            return Lyrics(
                pick.entry.synced, pick.entry.plain, SOURCE_LRCLIB,
                timingTrusted = trusted, timingRefId = refId, approxTiming = approx,
            )
        }

        // 4. Teks saja.
        if (own != null) return Lyrics(null, own.lines.joinToString("\n") { it.text }, own.source ?: SOURCE_YTM)
        pick?.entry?.plain?.let { return Lyrics(null, it, SOURCE_LRCLIB) }
        netError?.let { throw it }
        return Lyrics(null, null)
    }

    companion object {
        const val SOURCE_YTM = "YouTube Music"
        const val SOURCE_LRCLIB = "Sumber: LRCLIB"
    }
}
