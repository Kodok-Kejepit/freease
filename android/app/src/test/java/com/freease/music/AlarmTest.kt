package com.freease.music

import com.freease.music.data.alarm.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlarmTest {
    private val tz = TimeZone.getTimeZone("Asia/Jakarta")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    private fun next(a: Alarm, now: Long) = Alarm.nextTrigger(a, now, Calendar.getInstance(tz))

    @Test
    fun onceAlarm_laterToday_orTomorrow() {
        // Kamis 22 Okt 2026, 05:00
        assertEquals(at(2026, 10, 22, 6, 0), next(Alarm(1, 6, 0), at(2026, 10, 22, 5, 0)))
        assertEquals(at(2026, 10, 23, 6, 0), next(Alarm(1, 6, 0), at(2026, 10, 22, 6, 0)))
    }

    @Test
    fun weekdays_skipWeekend() {
        val workdays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val a = Alarm(1, 6, 30, workdays)
        // Jumat 23 Okt 07:00 → Senin 26 Okt 06:30
        assertEquals(at(2026, 10, 26, 6, 30), next(a, at(2026, 10, 23, 7, 0)))
        assertEquals("Hari kerja", a.daysText)
    }

    @Test
    fun singleDay_aWeekLater() {
        val a = Alarm(1, 8, 0, setOf(Calendar.FRIDAY))
        assertEquals(at(2026, 10, 30, 8, 0), next(a, at(2026, 10, 23, 8, 0)))
        assertEquals("Jum", a.daysText)
    }
}
