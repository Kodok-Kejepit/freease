package com.freease.music.data.monthly

import com.freease.music.data.db.AlbumStat
import com.freease.music.data.db.ArtistStat
import com.freease.music.data.db.LikedArtistStat
import com.freease.music.data.db.PlayStamp
import com.freease.music.data.db.SongStat
import com.freease.music.data.model.Song
import java.util.Calendar
import java.util.TimeZone

/** Satu bulan kalender (zona waktu perangkat). [month] 0..11 seperti Calendar. */
data class MonthKey(val year: Int, val month: Int) {
    fun start(zone: TimeZone = TimeZone.getDefault()): Long = cal(zone, year, month).timeInMillis

    /** Awal bulan berikutnya (batas atas eksklusif). */
    fun end(zone: TimeZone = TimeZone.getDefault()): Long = next().start(zone)

    fun next(): MonthKey = if (month == 11) MonthKey(year + 1, 0) else MonthKey(year, month + 1)
    fun prev(): MonthKey = if (month == 0) MonthKey(year - 1, 11) else MonthKey(year, month - 1)

    fun daysIn(zone: TimeZone = TimeZone.getDefault()): Int = cal(zone, year, month).getActualMaximum(Calendar.DAY_OF_MONTH)

    val label: String get() = "${NAMES[month]} $year"

    operator fun compareTo(o: MonthKey): Int = (year * 12 + month) - (o.year * 12 + o.month)

    companion object {
        val NAMES = listOf(
            "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember",
        )

        fun of(ts: Long, zone: TimeZone = TimeZone.getDefault()): MonthKey {
            val c = Calendar.getInstance(zone).apply { timeInMillis = ts }
            return MonthKey(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        }

        private fun cal(zone: TimeZone, year: Int, month: Int) = Calendar.getInstance(zone).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }
    }
}

/** Hasil analisis waktu dengar sebulan. Semua dihitung dari cap waktu, tanpa Android. */
data class Insights(
    val totalMin: Int,
    /** Menit dengar per hari (indeks 0 = tanggal 1). */
    val perDayMin: List<Float>,
    val activeDays: Int,
    val longestStreak: Int,
    val busiestDay: Int,
    val busiestDayMin: Int,
    val peakHour: Int,
    val nightShare: Double,
    val morningShare: Double,
)

object MonthlyInsights {
    fun compute(stamps: List<PlayStamp>, month: MonthKey, zone: TimeZone = TimeZone.getDefault()): Insights {
        val days = month.daysIn(zone)
        val perDayMs = LongArray(days)
        val perHourMs = LongArray(24)
        val cal = Calendar.getInstance(zone)
        for (s in stamps) {
            cal.timeInMillis = s.ts
            val d = cal.get(Calendar.DAY_OF_MONTH) - 1
            if (d in 0 until days) perDayMs[d] += s.ms
            perHourMs[cal.get(Calendar.HOUR_OF_DAY)] += s.ms
        }
        val total = perDayMs.sum()
        var streak = 0
        var longest = 0
        var active = 0
        for (ms in perDayMs) {
            if (ms >= ACTIVE_MS) {
                active++
                streak++
                if (streak > longest) longest = streak
            } else {
                streak = 0
            }
        }
        var busiest = 0
        for (i in perDayMs.indices) if (perDayMs[i] > perDayMs[busiest]) busiest = i
        var peak = 0
        for (h in 0 until 24) if (perHourMs[h] > perHourMs[peak]) peak = h
        val night = (22..23).sumOf { perHourMs[it] } + (0..3).sumOf { perHourMs[it] }
        val morning = (5..10).sumOf { perHourMs[it] }
        return Insights(
            totalMin = (total / 60_000).toInt(),
            perDayMin = perDayMs.map { it / 60_000f },
            activeDays = active,
            longestStreak = longest,
            busiestDay = busiest + 1,
            busiestDayMin = (perDayMs[busiest] / 60_000).toInt(),
            peakHour = peak,
            nightShare = if (total > 0) night.toDouble() / total else 0.0,
            morningShare = if (total > 0) morning.toDouble() / total else 0.0,
        )
    }

    /** Hari dihitung "aktif" kalau mendengar minimal 5 menit. */
    const val ACTIVE_MS = 5 * 60_000L
}

/** Pencapaian bulanan. [progress] 0..1; [earned] = progress sudah penuh. */
data class Achievement(
    val id: String,
    val title: String,
    val detail: String,
    val progress: Float,
) {
    val earned: Boolean get() = progress >= 1f
}

/** Tipe pendengar bulan ini, ditentukan dari kebiasaan (urutan prioritas dari atas). */
data class Persona(val id: String, val title: String, val blurb: String)

class MonthlyFacts(
    val insights: Insights,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val topSongPlays: Int,
    /** Porsi waktu dengar artis teratas (0..1). */
    val topArtistShare: Double,
    val likes: Int,
)

object MonthlyAchievements {
    fun persona(f: MonthlyFacts): Persona {
        val i = f.insights
        return when {
            i.totalMin < 30 -> Persona("newcomer", "Pendengar Baru", "Bulan ini baru pemanasan. Putar lebih banyak, rekapmu makin seru.")
            i.nightShare >= 0.4 -> Persona("owl", "Burung Hantu", "Sebagian besar musikmu mengalun di larut malam.")
            i.morningShare >= 0.4 -> Persona("early", "Si Pagi", "Harimu dimulai dengan musik sejak pagi.")
            f.topArtistShare >= 0.35 -> Persona("loyal", "Setia pada Satu", "Satu artis menguasai hampir separuh playlist hatimu.")
            f.distinctArtists >= 40 -> Persona("explorer", "Penjelajah", "Kamu menjelajah banyak artis yang berbeda.")
            i.busiestDayMin >= 240 -> Persona("marathon", "Maratonis", "Ada hari di mana musikmu tidak berhenti berjam-jam.")
            else -> Persona("easy", "Penikmat Santai", "Musik menemanimu dengan irama yang seimbang.")
        }
    }

    fun evaluate(f: MonthlyFacts): List<Achievement> {
        val i = f.insights
        fun p(value: Number, target: Number) = (value.toDouble() / target.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return listOf(
            Achievement("streak", "Rutin Berturut-turut", "Mendengarkan 7 hari berturut-turut (terbaik: ${i.longestStreak} hari)", p(i.longestStreak, 7)),
            Achievement("regular", "Penghuni Setia", "Aktif mendengarkan 20 hari dalam sebulan (${i.activeDays} hari)", p(i.activeDays, 20)),
            Achievement("marathon", "Maraton Musik", "Mendengarkan 3 jam dalam satu hari (terbaik: ${i.busiestDayMin} menit)", p(i.busiestDayMin, 180)),
            Achievement("hours", "20 Jam Bersama Musik", "Total 20 jam mendengarkan (${i.totalMin / 60} jam)", p(i.totalMin, 1200)),
            Achievement("explorer", "Penemu Lagu", "Memutar 50 lagu berbeda (${f.distinctSongs} lagu)", p(f.distinctSongs, 50)),
            Achievement("variety", "Lintas Artis", "Mendengarkan 25 artis berbeda (${f.distinctArtists} artis)", p(f.distinctArtists, 25)),
            Achievement("repeat", "Pengulang Sejati", "Memutar satu lagu 10 kali (terbaik: ${f.topSongPlays} kali)", p(f.topSongPlays, 10)),
            Achievement("hearts", "Kolektor Hati", "Menyukai 15 lagu dalam sebulan (${f.likes} lagu)", p(f.likes, 15)),
        )
    }
}

/** Satu laporan bulanan lengkap yang ditampilkan di layar dan dibagikan sebagai kartu. */
class MonthlyReport(
    val month: MonthKey,
    val insights: Insights,
    val persona: Persona,
    val achievements: List<Achievement>,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val topSongs: List<SongStat>,
    val topAlbums: List<AlbumStat>,
    val topArtists: List<ArtistStat>,
    val likedSongs: List<Song>,
    val likedArtists: List<LikedArtistStat>,
    /** Perubahan total menit dibanding bulan sebelumnya, atau null kalau bulan lalu kosong. */
    val deltaPercent: Int?,
) {
    val isEmpty: Boolean get() = insights.totalMin == 0 && likedSongs.isEmpty()
}
