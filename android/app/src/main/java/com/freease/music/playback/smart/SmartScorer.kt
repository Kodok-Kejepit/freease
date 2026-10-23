package com.freease.music.playback.smart

import com.freease.music.data.model.Song
import kotlin.math.exp
import kotlin.random.Random

/** Kandidat lagu beserta seberapa dekat ia dengan lagu-lagu yang sedang didengar. */
class Candidate(
    val song: Song,
    /** 0..1: gabungan kedekatan dari beberapa seed (peringkat di daftar "terkait" + bobot seed). */
    val seedScore: Double,
    val seedTitle: String?,
    val fromLiked: Boolean = false,
)

/** Keadaan sesi yang memengaruhi pilihan: antrean sekarang dan reaksi pengguna barusan. */
class PickContext(
    val queueIds: Set<String>,
    /** Kunci artis di sekitar posisi sekarang (lama → baru); dihindari supaya tidak berulang. */
    val tailArtists: List<String>,
    val explore: Double,
    val sessionArtistSkips: Map<String, Int>,
    val mood: SmartMood? = null,
)

/** Reaksi pada lagu sisipan dalam sesi ini → mengatur seberapa berani menawarkan hal baru. */
class SmartSession {
    private val outcomes = ArrayDeque<Boolean>()
    val artistSkips = HashMap<String, Int>()

    @Synchronized
    fun record(ok: Boolean) {
        outcomes.addLast(ok)
        while (outcomes.size > 6) outcomes.removeFirst()
    }

    @Synchronized
    fun noteArtist(key: String, skipped: Boolean) {
        val cur = artistSkips[key] ?: 0
        artistSkips[key] = if (skipped) cur + 1 else (cur - 1).coerceAtLeast(0)
    }

    /** 0.1 = main aman (artis familiar), 0.8 = berani menawarkan artis baru. */
    @get:Synchronized
    val explore: Double
        get() {
            var e = 0.4
            for (ok in outcomes) e += if (ok) 0.08 else -0.12
            return e.coerceIn(0.1, 0.8)
        }

    /** Jumlah lagu pengguna di antara dua sisipan. */
    @get:Synchronized
    val gap: Int
        get() = when {
            outcomes.size >= 2 && !outcomes.last() && !outcomes.elementAt(outcomes.size - 2) -> 5
            explore > 0.65 -> 2
            else -> 3
        }

    @Synchronized
    fun reset() {
        outcomes.clear()
        artistSkips.clear()
    }
}

object SmartScorer {
    class Scored(val candidate: Candidate, val score: Double, val why: String)

    private const val MIN_SEC = 60L
    private const val MAX_SEC = 600L

    fun score(c: Candidate, p: TasteProfile, ctx: PickContext): Scored {
        val song = c.song
        val key = Taste.key(song.artist)
        val a = p.artistAffinity(key)
        val tags = Taste.tagsOf(song.title)
        val tagFit = p.tagFit(tags)
        val unknownArtist = a == null || a <= 0.05

        var s = 0.0
        s += (a ?: 0.0) * 1.8
        s += c.seedScore * 1.3
        s += tagFit * 0.7
        // Sedang berani → hadiahi artis baru; sedang waspada → hadiahi artis akrab.
        s += if (unknownArtist) (ctx.explore - 0.3) * 0.9 else (0.3 - ctx.explore) * 0.3
        if (c.fromLiked) s += 0.2
        if (song.id in p.playedLatelyIds) s -= 0.6
        s -= 0.9 * (p.songSkips[song.id] ?: 0.0)
        s -= 1.2 * (ctx.sessionArtistSkips[key] ?: 0)
        if (song.durationSec in 1 until 90 || song.durationSec > 480) s -= 0.35
        val moodFit = ctx.mood?.fit(song) ?: 0.0
        if (ctx.mood != null) s += moodFit * 1.6

        val why = when {
            ctx.mood != null && moodFit >= 0.4 -> "Pas untuk suasana ${ctx.mood.label}"
            c.fromLiked -> "Dari lagu yang kamu sukai"
            a != null && a >= 0.6 -> "Kamu sering mendengarkan ${song.artist}"
            c.seedScore >= 0.55 && c.seedTitle != null -> "Mirip dengan ${c.seedTitle}"
            tagFit > 0.4 -> "Cocok dengan seleramu di ${p.band.label}"
            unknownArtist && ctx.explore >= 0.4 -> "Penemuan baru untuk kamu"
            a != null && a > 0.15 -> "Artis yang kamu suka: ${song.artist}"
            else -> "Dipilih dari lagu yang sedang kamu dengar"
        }
        return Scored(c, s, why)
    }

    /** Penyaring keras: lagu yang tidak boleh muncul apa pun skornya. */
    fun eligible(c: Candidate, p: TasteProfile, ctx: PickContext): Boolean {
        val s = c.song
        if (s.isLive || s.id in ctx.queueIds || s.id in p.blockedIds || s.id in p.recentIds) return false
        if (s.durationSec > 0 && s.durationSec !in MIN_SEC..MAX_SEC) return false
        if (Taste.isNoise(s.title)) return false
        if (ctx.mood != null && ctx.mood.fit(s) <= SmartMood.HARD_REJECT) return false
        val a = p.artistAffinity(Taste.key(s.artist))
        if (a != null && a <= -0.5) return false
        return true
    }

    /**
     * Memilih [n] lagu: dinilai dulu, lalu diambil satu per satu dengan sampling softmax dari
     * enam teratas (bukan selalu juara) dan penalti kalau artisnya baru saja/sudah terpilih.
     */
    fun choose(candidates: List<Candidate>, p: TasteProfile, ctx: PickContext, n: Int, rng: Random = Random.Default): List<Song> {
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        val pool = ArrayList<Scored>()
        for (c in candidates) {
            if (!eligible(c, p, ctx)) continue
            if (!seenIds.add(c.song.id)) continue
            if (!seenTitles.add(Taste.titleKey(c.song.title) + "|" + Taste.key(c.song.artist))) continue
            pool += score(c, p, ctx)
        }
        val chosen = ArrayList<Song>()
        val artistCount = HashMap<String, Int>()
        ctx.tailArtists.forEach { artistCount[it] = (artistCount[it] ?: 0) + 1 }
        val temperature = 0.3 + 0.5 * ctx.explore

        repeat(n) {
            if (pool.isEmpty()) return@repeat
            val adjusted = pool.map { it to it.score - 2.0 * (artistCount[Taste.key(it.candidate.song.artist)] ?: 0) }
                .sortedByDescending { it.second }
                .take(6)
            val top = adjusted.first().second
            val weights = adjusted.map { exp((it.second - top) / temperature) }
            var r = rng.nextDouble() * weights.sum()
            var idx = 0
            for (i in weights.indices) {
                r -= weights[i]
                if (r <= 0) { idx = i; break }
                idx = i
            }
            val pick = adjusted[idx].first
            pool.remove(pick)
            val key = Taste.key(pick.candidate.song.artist)
            artistCount[key] = (artistCount[key] ?: 0) + 2
            chosen += pick.candidate.song.copy(smartWhy = pick.why)
        }
        return chosen
    }
}
