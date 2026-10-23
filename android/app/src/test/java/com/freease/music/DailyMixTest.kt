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
