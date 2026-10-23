package com.freease.music.data.lyrics

import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.TitleCleaner
import kotlin.math.abs

/**
 * Aturan pencocokan lirik, murni tanpa jaringan supaya bisa diuji.
 *
 * Prinsipnya: waktu lirik (dari YouTube Music maupun LRCLIB) dibuat untuk versi AUDIO resmi lagu.
 * Video klip / unggahan lain sering punya intro atau potongan berbeda, jadi waktunya hanya dipercaya
 * kalau durasinya sama dengan versi audionya. Durasi di LRCLIB diisi pengunggah dan sering salah,
 * sehingga dipakai lewat "suara terbanyak", bukan satu entri.
 */
object LyricsMatch {
    /** Selisih durasi maksimum agar dua rekaman dianggap potongan yang sama. */
    const val SAME_CUT_SEC = 2L

    /** Kata penanda versi lain lagu. Ada di kandidat tapi tidak di judul kita → lagu versi lain. */
    private val versionWords = setOf(
        "acoustic", "akustik", "live", "remix", "cover", "instrumental", "karaoke", "slowed", "reverb",
        "sped", "nightcore", "lofi", "mashup", "medley", "8", "bit", "orchestra", "orkestra", "piano",
        "unplugged", "rework", "extended", "demo", "version", "versi", "ver", "edit", "koplo",
    )

    /** Bagian "feat./ft./with ..." tidak menentukan lagu; dibuang sebelum membandingkan judul. */
    private val featBracket = Regex("[(\\[](?:feat|ft|featuring|with|bersama)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val featTail = Regex("\\s(?:feat|ft|featuring)\\b.*$", RegexOption.IGNORE_CASE)

    fun stripFeat(s: String): String = s.replace(featBracket, " ").replace(featTail, "").trim()

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(s: String): List<String> = s.lowercase().split(splitter).filter { it.isNotEmpty() }

    /** Porsi kata [a] yang ada di [b] (0..1). */
    fun coverage(a: String, b: String): Double {
        val ta = tokens(a)
        if (ta.isEmpty()) return 0.0
        val tb = tokens(b).toSet()
        return ta.count { it in tb }.toDouble() / ta.size
    }

    fun versionMismatch(ours: String, theirs: String): Boolean {
        val mine = tokens(ours).toSet()
        return tokens(theirs).any { it in versionWords && it !in mine }
    }

    /**
     * Judul cocok kalau hampir semua kata judul kita ada di judul mereka dan sebaliknya (setelah membuang
     * "(Official Video)", "feat. ..." dan sejenisnya). Penanda versi diperiksa pada judul mentah supaya
     * "(Acoustic Version)" tidak ikut terbuang.
     */
    fun titleMatches(ours: String, theirs: String): Boolean {
        if (versionMismatch(ours, theirs)) return false
        val a = core(ours)
        val b = core(theirs)
        return coverage(a, b) >= 0.75 && coverage(b, a) >= 0.6
    }

    private fun core(s: String): String = stripFeat(TitleCleaner.cleanTitle(s)).ifEmpty { s }

    /**
     * Artis cocok kalau separuh kata salah satunya ada di yang lain. [titleHint] menolong unggahan
     * yang nama artisnya hanya ada di judul ("Artis - Judul" yang tidak terpecah).
     */
    fun artistMatches(ours: String, theirs: String, titleHint: String = ""): Boolean {
        if (tokens(ours).isEmpty() || tokens(theirs).isEmpty()) return false
        if (coverage(ours, theirs) >= 0.5 || coverage(theirs, ours) >= 0.5) return true
        return titleHint.isNotEmpty() && coverage(theirs, titleHint) >= 0.99
    }

    /**
     * Lagu [theirTitle]/[theirArtist] sama dengan video kita? Selain judul yang mirip, judul video yang
     * panjang ("NewJeans 'Hype Boy' Official MV") juga diterima kalau judul lagunya ada utuh di dalamnya
     * dan artisnya cocok.
     */
    fun songMatches(ourTitle: String, ourArtist: String, theirTitle: String, theirArtist: String): Boolean {
        if (versionMismatch(ourTitle, theirTitle)) return false
        val a = core(ourTitle)
        val b = core(theirTitle)
        if (tokens(b).isEmpty()) return false
        if (coverage(a, b) >= 0.75 && coverage(b, a) >= 0.6) return artistMatches(ourArtist, theirArtist, ourTitle)
        return coverage(b, a) >= 0.99 &&
            (coverage(theirArtist, ourTitle) >= 0.99 || artistMatches(ourArtist, theirArtist))
    }

    /**
     * Versi audio resmi di YouTube Music yang sama dengan lagu ini. Dicoba juga dengan judul dan artis
     * ditukar, untuk unggahan berformat "Judul - Artis" yang terbaca terbalik.
     */
    fun bestTrack(tracks: List<MusicTrack>, title: String, artist: String): MusicTrack? {
        val top = tracks.take(6)
        return top.firstOrNull { songMatches(title, artist, it.title, it.artists) }
            ?: top.firstOrNull { songMatches(artist, title, it.title, it.artists) }
    }

    /** Judul video kita menandakan versi lain (cover, live, akustik...) yang tidak ada di judul lagu aslinya. */
    fun isOtherVersion(ourTitle: String, originalTitle: String): Boolean = versionMismatch(originalTitle, ourTitle)

    /** Perkiraan durasi lagu dari hasil LRCLIB, untuk menolak video kompilasi. 0 = tidak diketahui. */
    fun lrcSongSec(p: LrcPick): Long = p.typicalDurationSec
        ?: p.entry.synced?.lastOrNull()?.let { it.timeMs / 1000 + 15 }
        ?: Math.round(p.entry.durationSec).takeIf { it >= 30 }
        ?: 0

    /** Video jauh lebih panjang dari lagunya (kompilasi, mix 1 jam): bukan lagu yang sama. */
    fun isCompilation(videoSec: Long, songSec: Long): Boolean = videoSec > 0 && songSec > 0 && videoSec > songSec * 3 / 2 + 30

    fun sameCut(a: Long, b: Long): Boolean = a > 0 && b > 0 && abs(a - b) <= SAME_CUT_SEC

    /** Durasi hampir sama (beda intro/outro beberapa detik): waktu lirik kira-kira masih pas. */
    fun nearCut(a: Long, b: Long): Boolean = a > 0 && b > 0 && abs(a - b) <= NEAR_CUT_SEC

    const val NEAR_CUT_SEC = 8L

    // ------------------------------------------------------------------ LRCLIB

    class LrcPick(val entry: LrcEntry, val support: Int, val typicalDurationSec: Long?)

    /**
     * Pilih lirik LRCLIB. Salinan lirik yang sama sering diunggah berkali-kali dengan durasi berbeda;
     * entri dikelompokkan menurut isi waktunya, lalu durasi "asli" kelompok diambil dari yang terbanyak.
     */
    fun pickLrc(entries: List<LrcEntry>, title: String, artist: String, durationSec: Long): LrcPick? {
        val ok = entries.filter { songMatches(title, artist, it.title, it.artist) }
        val synced = ok.filter { it.synced != null }
        if (synced.isNotEmpty()) {
            val groups = synced.groupBy { signature(it.synced!!) }.values.map { g -> g to typicalDuration(g) }
            // Kelompok yang durasi aslinya sama dengan video ini diutamakan, lalu yang paling banyak salinannya.
            val (best, typical) = groups.maxWith(
                compareBy<Pair<List<LrcEntry>, Long?>> { (_, t) -> if (t != null && sameCut(t, durationSec)) 1 else 0 }
                    .thenBy { (g, _) -> g.size },
            )
            val rep = best.minBy { if (durationSec > 0) abs(it.durationSec - durationSec) else 0.0 }
            return LrcPick(rep, best.size, typical)
        }
        val plain = ok.firstOrNull { !it.plain.isNullOrBlank() } ?: return null
        return LrcPick(plain, 1, null)
    }

    /** Waktu lirik LRCLIB boleh langsung diikuti untuk video ini? */
    fun trustLrc(pick: LrcPick, durationSec: Long, isAudioTrack: Boolean, refDurationSec: Long?): Boolean {
        val lines = pick.entry.synced ?: return false
        val last = lines.lastOrNull()?.timeMs ?: 0
        if (durationSec > 0 && last > durationSec * 1000 + 2_000) return false
        val typical = pick.typicalDurationSec
        return when {
            // Versi audio resmi: LRCLIB memang dibuat untuknya, kecuali kelompoknya jelas untuk potongan lain.
            isAudioTrack -> typical == null || abs(typical - durationSec) <= 3
            refDurationSec != null -> sameCut(durationSec, refDurationSec) && (typical == null || abs(typical - refDurationSec) <= 3)
            else -> typical != null && sameCut(typical, durationSec)
        }
    }

    /**
     * Waktu lirik LRCLIB ini kemungkinan dibuat untuk rekaman berdurasi [recordingSec] (versi lagu resmi),
     * sehingga bisa diselaraskan otomatis dari rekaman itu ke video yang diputar.
     */
    fun lrcFitsRecording(pick: LrcPick, recordingSec: Long): Boolean {
        val lines = pick.entry.synced ?: return false
        if (recordingSec <= 0) return false
        if ((lines.lastOrNull()?.timeMs ?: 0) > recordingSec * 1000 + 2_000) return false
        val typical = pick.typicalDurationSec
        return typical == null || abs(typical - recordingSec) <= 3
    }

    /** Sidik isi lirik tersinkron: jumlah baris + waktu beberapa baris awal (dibulatkan 0,1 dtk). */
    private fun signature(lines: List<LyricLine>): String =
        lines.size.toString() + ":" + lines.take(5).joinToString(",") { (it.timeMs / 100).toString() }

    /** Durasi yang paling sering muncul dalam kelompok (entri < 30 dtk dianggap sampah). */
    private fun typicalDuration(group: List<LrcEntry>): Long? {
        val durations = group.map { Math.round(it.durationSec) }.filter { it >= 30 }
        if (durations.isEmpty()) return null
        // Hitung dukungan tiap durasi dengan toleransi ±2 dtk.
        return durations.maxWith(compareBy<Long> { d -> durations.count { abs(it - d) <= SAME_CUT_SEC } }.thenBy { -it })
    }
}
