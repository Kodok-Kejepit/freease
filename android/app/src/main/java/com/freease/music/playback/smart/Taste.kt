package com.freease.music.playback.smart

import java.util.Calendar
import kotlin.math.max
import kotlin.math.pow

/** Alat murni (tanpa Android) untuk membaca selera dari judul, waktu, dan perilaku dengar. */
object Taste {
    const val HALF_LIFE_MS = 21L * 24 * 3600_000
    const val SKIP_HALF_LIFE_MS = 14L * 24 * 3600_000

    enum class Band(val label: String) {
        NIGHT("malam harimu"),
        MORNING("pagimu"),
        DAY("siangmu"),
        EVENING("soremu"),
    }

    enum class Outcome { COMPLETE, SKIP }

    /** Kunci artis yang tahan beda huruf besar/spasi/tanda baca. */
    fun key(artist: String): String = artist.lowercase().filter { it.isLetterOrDigit() }

    /** Titel dinormalkan untuk membuang duplikat (versi lirik/official dari lagu yang sama). */
    fun titleKey(title: String): String = title.lowercase()
        .replace(Regex("[(\\[].*?[)\\]]"), "")
        .filter { it.isLetterOrDigit() }

    fun decay(ageMs: Long, halfLife: Long = HALF_LIFE_MS): Double =
        0.5.pow(max(0L, ageMs).toDouble() / halfLife)

    fun bandOf(hour: Int): Band = when (hour) {
        in 6..11 -> Band.MORNING
        in 12..17 -> Band.DAY
        in 18..21 -> Band.EVENING
        else -> Band.NIGHT
    }

    fun hourOf(ts: Long, cal: Calendar = Calendar.getInstance()): Int {
        cal.timeInMillis = ts
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /**
     * Lagu dianggap dilewati kalau ditekan "berikutnya" sebelum 35% durasi (atau 30 detik
     * bila durasi tidak diketahui); selesai kalau putar otomatis lanjut atau ≥80% didengar.
     */
    fun classify(durationSec: Long, listenedMs: Long, manualSkip: Boolean, autoEnd: Boolean): Outcome? {
        val dur = durationSec * 1000
        val frac = if (dur > 0) listenedMs.toDouble() / dur else null
        return when {
            manualSkip && (listenedMs < 8_000 || (frac != null && frac < 0.35) || (frac == null && listenedMs < 30_000)) -> Outcome.SKIP
            autoEnd && (frac == null || frac >= 0.5) -> Outcome.COMPLETE
            frac != null && frac >= 0.8 -> Outcome.COMPLETE
            else -> null
        }
    }

    // ------------------------------------------------------------------ tag judul

    private val tagRules: List<Pair<String, Regex>> = listOf(
        "remix" to Regex("\\b(remix|rmx|bootleg)\\b"),
        "acoustic" to Regex("\\b(acoustic|akustik|unplugged)\\b"),
        "live" to Regex("\\b(live|konser|concert)\\b"),
        "cover" to Regex("\\b(cover|covered)\\b"),
        "lofi" to Regex("\\b(lo-?fi|chill|study|santai)\\b"),
        "slowed" to Regex("\\b(slowed|reverb|sped up|speed up|nightcore|8d)\\b"),
        "instrumental" to Regex("\\b(instrumental|piano|orchestra|ost|soundtrack)\\b"),
        "dj" to Regex("\\b(dj|koplo|dugem|breakbeat)\\b"),
        "dangdut" to Regex("\\b(dangdut|campursari|pop jawa|jaipong)\\b"),
        "religi" to Regex("\\b(sholawat|nasyid|murottal|islami|gospel|worship)\\b"),
        "rock" to Regex("\\b(rock|metal|punk|grunge)\\b"),
        "hiphop" to Regex("\\b(rap|hip ?hop|trap|drill)\\b"),
        "ballad" to Regex("\\b(ballad|galau|sad|patah hati)\\b"),
    )

    private val excluded = Regex(
        "\\b(karaoke|backing track|tutorial|reaction|podcast|full album|compilation|non-?stop|mashup|" +
            "1 hour|2 hours|10 hours|how to|review|asmr)\\b",
    )

    private val idWords = setOf(
        "aku", "kamu", "kau", "cinta", "hati", "rindu", "sayang", "yang", "dan", "tak", "tidak",
        "untuk", "dengan", "dalam", "kita", "dia", "pulang", "kembali", "bukan", "jangan", "tanpa",
        "hanya", "masih", "sudah", "ingin", "luka", "sendiri", "bahagia", "kenangan", "maafkan", "mu", "ku",
    )

    /** Judul yang jelas bukan lagu (karaoke, tutorial, kompilasi berjam-jam, dll). */
    fun isNoise(title: String): Boolean = excluded.containsMatchIn(title.lowercase())

    /** Penanda gaya + aksara + bahasa dari judul. Murah: hanya regex dan satu pass karakter. */
    fun tagsOf(title: String): Set<String> {
        val t = title.lowercase()
        val out = HashSet<String>()
        for ((tag, rule) in tagRules) if (rule.containsMatchIn(t)) out += tag

        var latin = 0
        val other = HashMap<String, Int>()
        for (ch in t) {
            if (!ch.isLetter()) continue
            val name = when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.LATIN -> { latin++; null }
                Character.UnicodeScript.HANGUL -> "hangul"
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "kana"
                Character.UnicodeScript.HAN -> "han"
                Character.UnicodeScript.ARABIC -> "arabic"
                Character.UnicodeScript.CYRILLIC -> "cyrillic"
                Character.UnicodeScript.THAI -> "thai"
                Character.UnicodeScript.DEVANAGARI -> "devanagari"
                else -> null
            }
            if (name != null) other[name] = (other[name] ?: 0) + 1
        }
        other.forEach { (name, n) -> if (n >= 2) out += "script:$name" }
        if (other.isEmpty() && latin > 0) out += "script:latin"

        val words = t.split(Regex("[^a-z]+"))
        if (words.count { it in idWords } >= 2) out += "lang:id"
        return out
    }
}
