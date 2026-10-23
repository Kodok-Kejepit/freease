package com.freease.music.util

import java.util.Calendar
import java.util.Locale

object Format {
    fun duration(sec: Long): String {
        if (sec <= 0) return "--:--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000)

    fun longDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "$h jam $m mnt"
            m > 0 -> "$m mnt"
            else -> "${totalSec} dtk"
        }
    }

    fun compact(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> trim(n / 1_000_000_000.0) + " M"
        n >= 1_000_000 -> trim(n / 1_000_000.0) + " jt"
        n >= 1_000 -> trim(n / 1_000.0) + " rb"
        else -> n.toString()
    }

    private fun trim(v: Double): String {
        val s = String.format(Locale("id"), "%.1f", v)
        return s.removeSuffix(",0")
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    /** "Hari ini", "Kemarin", "3 hari lalu", atau tanggal. */
    fun relativeDay(ts: Long): String {
        val cal = Calendar.getInstance()
        fun day(t: Long): Long { cal.timeInMillis = t; return cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR) }
        val today = day(System.currentTimeMillis())
        val d = day(ts)
        return when {
            d == today -> "Hari ini"
            d == today - 1 -> "Kemarin"
            today - d in 2..6 -> "${today - d} hari lalu"
            else -> java.text.SimpleDateFormat("d MMM yyyy", Locale("id")).format(java.util.Date(ts))
        }
    }

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> "Selamat pagi"
        in 11..14 -> "Selamat siang"
        in 15..17 -> "Selamat sore"
        else -> "Selamat malam"
    }
}
