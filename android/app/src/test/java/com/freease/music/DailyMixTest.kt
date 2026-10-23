package com.freease.music

import com.freease.music.data.db.TasteEvent
import com.freease.music.data.mix.MixPlanner
import com.freease.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMixTest {
    private var t = 1_000_000_000_000L

    /** Satu sesi dengar: lagu-lagu berurutan berjarak 4 menit, lalu jeda 3 jam. */
    private fun session(vararg artists: String): List<TasteEvent> {
        val out = artists.mapIndexed { i, a -> TasteEvent("$a-$i-$t", a, "Lagu $i", t + i * 240_000L, 200_000, 220) }
        t += 3 * 3600_000L
        return out
    }

    @Test
    fun groupsArtistsHeardTogether() {
        val events = buildList {
            repeat(4) { addAll(session("Tulus", "Nadin Amizah", "Hindia", "Tulus", "Nadin Amizah")) }
            repeat(4) { addAll(session("NewJeans", "IVE", "LE SSERAFIM", "NewJeans")) }
            addAll(session("Sekali Saja"))
        }
        val plan = MixPlanner.plan(events)
        assertEquals(2, plan.size)
        assertEquals(setOf("tulus", "nadinamizah", "hindia"), plan[0].keys)
        assertEquals(setOf("newjeans", "ive", "lesserafim"), plan[1].keys)
    }

    @Test
    fun shortListensIgnored() {
        val events = (0 until 10).map { TasteEvent("x$it", "Skip Band", "t", t + it * 60_000L, 5_000, 200) }
        assertTrue(MixPlanner.plan(events).isEmpty())
    }

    @Test
    fun interleaveTwoKnownOneNew() {
        val own = (1..6).map { Song("o$it", "Own $it", "A") }
        val fresh = (1..6).map { Song("f$it", "Fresh $it", "B") }
        val mix = MixPlanner.interleave(own, fresh, max = 9)
        assertEquals(listOf("o1", "o2", "f1", "o3", "o4", "f2", "o5", "o6", "f3"), mix.map { it.id })
    }
}

class WrappedTest {
    @org.junit.Test
    fun yearlyInsights() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Jakarta")
        fun at(m: Int, d: Int, h: Int) = java.util.Calendar.getInstance(tz).apply { clear(); set(2026, m, d, h, 0) }.timeInMillis
        val stamps = buildList {
            // 10 hari berturut-turut di Maret, jam 22.
            for (d in 1..10) add(com.freease.music.data.db.PlayStamp(at(2, d, 22), 20 * 60_000L))
            // Satu hari maraton di Agustus.
            add(com.freease.music.data.db.PlayStamp(at(7, 17, 14), 300 * 60_000L))
            // Tahun lain diabaikan.
            add(com.freease.music.data.db.PlayStamp(java.util.Calendar.getInstance(tz).apply { clear(); set(2025, 11, 31, 12, 0) }.timeInMillis, 999 * 60_000L))
        }
        val i = com.freease.music.data.wrapped.YearlyInsights.compute(stamps, 2026, tz)
        org.junit.Assert.assertEquals(500, i.totalMin)
        org.junit.Assert.assertEquals(7, i.topMonth) // Agustus (300 mnt) > Maret (200 mnt)
        org.junit.Assert.assertEquals(10, i.longestStreak)
        org.junit.Assert.assertEquals(11, i.activeDays)
        org.junit.Assert.assertEquals(300, i.busiestDayMin)
        org.junit.Assert.assertEquals(14, i.peakHour)
        org.junit.Assert.assertEquals("owl", com.freease.music.data.wrapped.YearlyInsights.persona(i.copy(nightShare = 0.5), 20, 0.1).id)
    }
}
