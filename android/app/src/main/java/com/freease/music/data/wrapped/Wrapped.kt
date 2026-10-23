package com.freease.music.data.wrapped

import com.freease.music.data.db.ArtistStat
import com.freease.music.data.db.PlayStamp
import com.freease.music.data.db.SongStat
import com.freease.music.data.monthly.Persona
import java.util.Calendar
import java.util.TimeZone

/** Kebiasaan dengar setahun penuh; dihitung dari cap waktu saja (tanpa Android). */
data class YearInsights(
    val totalMin: Int,
    /** Menit dengar per bulan (0 = Januari). */
    val perMonthMin: List<Int>,
    val topMonth: Int,
    val activeDays: Int,
    val longestStreak: Int,
    /** Hari paling banyak mendengar (hari ke-n dalam tahun, mulai 1) dan menitnya. */
    val busiestDayOfYear: Int,
    val busiestDayMin: Int,
    val peakHour: Int,
    val nightShare: Double,
    val morningShare: Double,
)

object YearlyInsights {
    fun compute(stamps: List<PlayStamp>, year: Int, zone: TimeZone = TimeZone.getDefault()): YearInsights {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, 0, 1)
        val days = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val perDay = LongArray(days)
        val perMonth = LongArray(12)
        val perHour = LongArray(24)
        for (s in stamps) {
            cal.timeInMillis = s.ts
            if (cal.get(Calendar.YEAR) != year) continue
            perDay[cal.get(Calendar.DAY_OF_YEAR) - 1] += s.ms
            perMonth[cal.get(Calendar.MONTH)] += s.ms
            perHour[cal.get(Calendar.HOUR_OF_DAY)] += s.ms
        }
        var streak = 0
        var longest = 0
        var active = 0
        for (ms in perDay) {
            if (ms >= ACTIVE_MS) {
                active++
                streak++
                longest = maxOf(longest, streak)
            } else {
                streak = 0
            }
        }
        val total = perDay.sum()
        val busiest = perDay.indices.maxByOrNull { perDay[it] } ?: 0
        val night = (22..23).sumOf { perHour[it] } + (0..3).sumOf { perHour[it] }
        val morning = (5..10).sumOf { perHour[it] }
        return YearInsights(
            totalMin = (total / 60_000).toInt(),
            perMonthMin = perMonth.map { (it / 60_000).toInt() },
            topMonth = perMonth.indices.maxByOrNull { perMonth[it] } ?: 0,
            activeDays = active,
            longestStreak = longest,
            busiestDayOfYear = busiest + 1,
            busiestDayMin = (perDay[busiest] / 60_000).toInt(),
            peakHour = perHour.indices.maxByOrNull { perHour[it] } ?: 0,
            nightShare = if (total > 0) night.toDouble() / total else 0.0,
            morningShare = if (total > 0) morning.toDouble() / total else 0.0,
        )
    }

    /** Tipe pendengar setahun (ambang disesuaikan untuk 12 bulan). */
    fun persona(i: YearInsights, distinctArtists: Int, topArtistShare: Double): Persona = when {
        i.totalMin < 300 -> Persona("newcomer", "Pendengar Baru", "Perjalanan musikmu di Freease baru dimulai — tahun depan pasti lebih seru.")
        i.nightShare >= 0.4 -> Persona("owl", "Burung Hantu", "Saat orang lain tidur, playlist-mu justru baru mulai.")
        i.morningShare >= 0.4 -> Persona("early", "Si Pagi", "Musik selalu jadi teman pertamamu menyambut hari.")
        topArtistShare >= 0.3 -> Persona("loyal", "Setia pada Satu", "Satu artis menguasai tahunmu. Itu namanya kesetiaan.")
        distinctArtists >= 150 -> Persona("explorer", "Penjelajah", "Ratusan artis kamu jelajahi. Telingamu tak kenal batas.")
        i.longestStreak >= 60 -> Persona("devoted", "Tak Pernah Absen", "Berbulan-bulan tanpa sehari pun tanpa musik.")
        else -> Persona("easy", "Penikmat Santai", "Musik menemanimu dengan irama yang seimbang sepanjang tahun.")
    }

    const val ACTIVE_MS = 5 * 60_000L
}

class WrappedReport(
    val year: Int,
    val insights: YearInsights,
    val topSongs: List<SongStat>,
    val topArtists: List<ArtistStat>,
    val distinctSongs: Int,
    val distinctArtists: Int,
    val persona: Persona,
    /** Porsi waktu dengar artis teratas (0..1). */
    val topArtistShare: Double,
) {
    val isEmpty: Boolean get() = insights.totalMin < 10 || topSongs.isEmpty()
}
