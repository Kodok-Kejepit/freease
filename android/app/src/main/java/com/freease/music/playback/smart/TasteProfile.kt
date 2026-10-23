package com.freease.music.playback.smart

import com.freease.music.data.db.TasteEvent
import com.freease.music.data.model.Song
import com.freease.music.data.taste.ArtistSignal
import com.freease.music.data.taste.SongSignal
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ringkasan selera pengguna yang dihitung dari riwayat dengar, lagu disukai, dan sinyal
 * lewati/selesai/jempol. Semua nilai dinormalkan ke -1..1 supaya mudah digabung di skor.
 */
class TasteProfile(
    /** Afinitas per artis (kunci [Taste.key]); negatif = sering dilewati/ditolak. */
    val artist: Map<String, Double>,
    private val tagShare: Map<String, Double>,
    private val bandShare: Map<String, Double>,
    private val bandWeight: Double,
    /** 0..1 — seberapa banyak data yang ada; selera tag baru dipercaya penuh setelah cukup riwayat. */
    val confidence: Double,
    val liked: List<Song>,
    val likedIds: Set<String>,
    val recentIds: Set<String>,
    val playedLatelyIds: Set<String>,
    val blockedIds: Set<String>,
    val songSkips: Map<String, Double>,
    val band: Taste.Band,
) {
    fun artistAffinity(key: String): Double? = artist[key]

    /** Kecocokan gaya/bahasa judul dengan kebiasaan dengar (global + jam sekarang). */
    fun tagFit(tags: Set<String>): Double {
        if (tags.isEmpty()) return 0.0
        var sum = 0.0
        for (t in tags) {
            val g = tagShare[t] ?: 0.0
            val b = if (bandWeight >= 5.0) (bandShare["${band.name}:$t"] ?: 0.0) else g
            val share = 0.65 * g + 0.35 * b
            sum += ((share - 0.35) * 2.0).coerceIn(-0.7, 1.0)
        }
        return (sum / tags.size) * confidence
    }

    companion object {
        val EMPTY = TasteProfile(
            emptyMap(), emptyMap(), emptyMap(), 0.0, 0.0, emptyList(), emptySet(), emptySet(),
            emptySet(), emptySet(), emptyMap(), Taste.Band.DAY,
        )

        fun build(
            now: Long,
            events: List<TasteEvent>,
            liked: List<Song>,
            songSignals: List<SongSignal>,
            artistSignals: List<ArtistSignal>,
            hour: Int,
        ): TasteProfile {
            val band = Taste.bandOf(hour)
            val cal = Calendar.getInstance()
            val artistRaw = HashMap<String, Double>()
            val tagRaw = HashMap<String, Double>()
            val bandRaw = HashMap<String, Double>()
            var total = 0.0
            var bandTotal = 0.0
            val recent = HashSet<String>()
            val lately = HashSet<String>()

            for (e in events) {
                val dur = e.durationSec * 1000
                val completion = if (dur > 0) (e.playedMs.toDouble() / dur).coerceIn(0.0, 1.0) else 0.7
                val w = completion * Taste.decay(now - e.ts)
                if (w <= 0.0) continue
                total += w
                val k = Taste.key(e.artist)
                artistRaw[k] = (artistRaw[k] ?: 0.0) + w
                val sameBand = Taste.bandOf(Taste.hourOf(e.ts, cal)) == band
                if (sameBand) bandTotal += w
                for (t in Taste.tagsOf(e.title)) {
                    tagRaw[t] = (tagRaw[t] ?: 0.0) + w
                    if (sameBand) bandRaw["${band.name}:$t"] = (bandRaw["${band.name}:$t"] ?: 0.0) + w
                }
                val age = now - e.ts
                if (age < 12 * 3600_000L) recent += e.songId
                if (age < 3 * 24 * 3600_000L) lately += e.songId
            }

            for (s in liked) {
                val k = Taste.key(s.artist)
                artistRaw[k] = (artistRaw[k] ?: 0.0) + 1.5
                total += 0.8
                for (t in Taste.tagsOf(s.title)) tagRaw[t] = (tagRaw[t] ?: 0.0) + 0.8
            }

            for (a in artistSignals) {
                val effSkips = max(0.0, a.skips - a.completes * 0.5) * Taste.decay(now - a.lastAt, Taste.SKIP_HALF_LIFE_MS)
                artistRaw[a.key] = (artistRaw[a.key] ?: 0.0) - 0.6 * effSkips - 3.0 * a.downs + 0.8 * a.ups
            }

            val maxPos = max(artistRaw.values.maxOrNull() ?: 0.0, 1e-6)
            // Akar kuadrat: artis yang cukup sering didengar tetap berarti, tidak kalah jauh dari artis nomor satu.
            val artist = artistRaw.mapValues { (_, v) ->
                val r = sqrt(min(abs(v) / maxPos, 1.0))
                if (v >= 0) r else -r
            }

            val tagTotal = max(total, 1e-6)
            val tagShare = tagRaw.mapValues { (_, v) -> (v / tagTotal).coerceIn(0.0, 1.0) }
            val bandShare = bandRaw.mapValues { (_, v) -> (v / max(bandTotal, 1e-6)).coerceIn(0.0, 1.0) }

            val blocked = songSignals.filter { it.downs > 0 }.map { it.songId }.toHashSet()
            val skips = songSignals.associate {
                it.songId to max(0.0, it.skips - it.completes * 0.5) * Taste.decay(now - it.lastAt, Taste.SKIP_HALF_LIFE_MS)
            }.filterValues { it > 0.0 }

            return TasteProfile(
                artist = artist,
                tagShare = tagShare,
                bandShare = bandShare,
                bandWeight = bandTotal,
                confidence = min(1.0, total / 15.0),
                liked = liked,
                likedIds = liked.map { it.id }.toHashSet(),
                recentIds = recent,
                playedLatelyIds = lately,
                blockedIds = blocked,
                songSkips = skips,
                band = band,
            )
        }
    }
}
