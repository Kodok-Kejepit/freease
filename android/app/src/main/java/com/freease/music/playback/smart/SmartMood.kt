package com.freease.music.playback.smart

import com.freease.music.data.model.Song

/**
 * Suasana untuk Freease Pintar. Tiap suasana punya kata kunci pencarian untuk mencari
 * kandidat dan aturan "kecocokan" (-1..1) dari judul + durasi untuk menilai tiap lagu.
 */
enum class SmartMood(
    val id: String,
    val label: String,
    val blurb: String,
    val queries: List<String>,
    private val goodTags: Set<String>,
    private val badTags: Set<String>,
    private val goodWords: Regex,
    private val badWords: Regex,
    /** Jumlah lagu pengguna di antara dua sisipan selama suasana ini aktif. */
    val gap: Int,
    /** Lagu yang lebih pendek dari ini (detik) kurang cocok. */
    private val minSec: Int = 0,
) {
    FOCUS(
        "focus", "Fokus", "Instrumental dan tenang untuk belajar atau bekerja",
        listOf("focus music instrumental", "deep focus study music", "lofi study beats", "piano instrumental concentration"),
        setOf("instrumental", "lofi", "acoustic"), setOf("remix", "dj", "rock", "hiphop", "live", "dangdut", "slowed"),
        Regex("\\b(focus|study|concentrat\\w*|deep work|ambient|piano|instrumental|lo-?fi|fokus|belajar)\\b"),
        Regex("\\b(party|hype|bass boosted|workout|gym)\\b"),
        gap = 2, minSec = 90,
    ),
    CHILL(
        "chill", "Santai", "Akustik dan mellow untuk menemani bersantai",
        listOf("chill music playlist", "santai acoustic indonesia", "lofi chill beats", "acoustic chill vibes"),
        setOf("lofi", "acoustic", "ballad"), setOf("remix", "dj", "rock", "hiphop", "live"),
        Regex("\\b(chill|relax|santai|acoustic|akustik|easy|mellow|cafe|coffee|sunset)\\b"),
        Regex("\\b(workout|gym|party|hype|bass boosted)\\b"),
        gap = 2, minSec = 90,
    ),
    WORKOUT(
        "workout", "Olahraga", "Beat kencang dan energik untuk semangat berolahraga",
        listOf("workout music playlist", "gym motivation music", "running playlist upbeat", "edm workout hits"),
        setOf("remix", "dj", "rock", "hiphop"), setOf("lofi", "slowed", "acoustic", "ballad", "religi", "instrumental"),
        Regex("\\b(workout|gym|run|running|power|energy|beast|pump|hype|bass|edm|trap|phonk|cardio)\\b"),
        Regex("\\b(sleep|relax|lullaby|calm|sad|tidur)\\b"),
        gap = 2, minSec = 100,
    ),
    SLEEP(
        "sleep", "Tidur", "Lembut dan menenangkan sampai kamu terlelap",
        listOf("sleep music relaxing", "lullaby calm piano", "ambient sleep music", "soft piano sleep"),
        setOf("instrumental", "lofi"), setOf("remix", "dj", "rock", "hiphop", "live", "dangdut"),
        Regex("\\b(sleep|tidur|lullaby|calm|relax|soft|ambient|rain|piano|meditat\\w*|healing)\\b"),
        Regex("\\b(workout|gym|party|hype|bass|energetic)\\b"),
        gap = 1, minSec = 120,
    );

    /** Seberapa cocok [song] dengan suasana ini: -1 (jelas tidak) .. 1 (sangat pas). */
    fun fit(song: Song): Double = fit(song.title, song.durationSec)

    fun fit(title: String, durationSec: Long): Double {
        val t = title.lowercase()
        val tags = Taste.tagsOf(title)
        var v = 0.0
        if (tags.any { it in goodTags }) v += 0.5
        if (goodWords.containsMatchIn(t)) v += 0.5
        if (tags.any { it in badTags }) v -= 0.8
        if (badWords.containsMatchIn(t)) v -= 0.8
        if (durationSec in 1 until minSec.toLong()) v -= 0.3
        return v.coerceIn(-1.0, 1.0)
    }

    companion object {
        fun byId(id: String?): SmartMood? = entries.firstOrNull { it.id == id }

        /** Lagu di bawah ini ditolak keras untuk suasana (tanpa melihat skor lain). */
        const val HARD_REJECT = -0.8
    }
}
