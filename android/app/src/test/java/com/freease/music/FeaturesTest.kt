package com.freease.music

import com.freease.music.data.DataUsage
import com.freease.music.data.backup.Backup
import com.freease.music.data.backup.BackupArtist
import com.freease.music.data.backup.BackupData
import com.freease.music.data.backup.BackupPlaylist
import com.freease.music.data.db.PlayStamp
import com.freease.music.data.download.AutoDownloadPlanner
import com.freease.music.data.download.DlCandidate
import com.freease.music.data.download.DlHeld
import com.freease.music.data.model.Song
import com.freease.music.data.monthly.Insights
import com.freease.music.data.monthly.MonthKey
import com.freease.music.data.monthly.MonthlyAchievements
import com.freease.music.data.monthly.MonthlyFacts
import com.freease.music.data.monthly.MonthlyInsights
import com.freease.music.playback.SleepTimer
import com.freease.music.playback.smart.Candidate
import com.freease.music.playback.smart.PickContext
import com.freease.music.playback.smart.SmartMood
import com.freease.music.playback.smart.SmartScorer
import com.freease.music.playback.smart.TasteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

class FeaturesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val oct = MonthKey(2026, 9)

    private fun ts(day: Int, hour: Int, month: MonthKey = oct): Long =
        Calendar.getInstance(utc).apply { clear(); set(month.year, month.month, day, hour, 0, 0) }.timeInMillis

    // ------------------------------------------------------------ Monthly

    @Test fun monthKeyMath() {
        assertEquals("Oktober 2026", oct.label)
        assertEquals(MonthKey(2026, 10), oct.next())
        assertEquals(MonthKey(2027, 0), MonthKey(2026, 11).next())
        assertEquals(MonthKey(2025, 11), MonthKey(2026, 0).prev())
        assertEquals(31, oct.daysIn(utc))
        assertEquals(29, MonthKey(2024, 1).daysIn(utc))
        assertEquals(oct.end(utc), MonthKey(2026, 10).start(utc))
        assertTrue(MonthKey(2026, 10) > oct)
        assertEquals(oct, MonthKey.of(ts(15, 12), utc))
    }

    @Test fun insightsStreakNightAndBusiestDay() {
        val min = 60_000L
        val stamps = buildList {
            // hari 1..3 berturut-turut (10 menit, jam 23 = malam), hari 5 hanya 2 menit (tidak aktif), hari 10 sebanyak 200 menit.
            for (d in 1..3) add(PlayStamp(ts(d, 23), 10 * min))
            add(PlayStamp(ts(5, 9), 2 * min))
            add(PlayStamp(ts(10, 14), 200 * min))
        }
        val i = MonthlyInsights.compute(stamps, oct, utc)
        assertEquals(31, i.perDayMin.size)
        assertEquals(4, i.activeDays) // 1,2,3 dan 10
        assertEquals(3, i.longestStreak)
        assertEquals(10, i.busiestDay)
        assertEquals(200, i.busiestDayMin)
        assertEquals(14, i.peakHour)
        assertEquals(232, i.totalMin)
        assertEquals(30.0 / 232.0, i.nightShare, 0.001)
    }

    @Test fun emptyMonthHasNoActivity() {
        val i = MonthlyInsights.compute(emptyList(), oct, utc)
        assertEquals(0, i.totalMin)
        assertEquals(0, i.activeDays)
        assertEquals(0.0, i.nightShare, 0.0)
    }

    private fun insights(total: Int = 600, active: Int = 10, streak: Int = 3, busiest: Int = 90, night: Double = 0.1, morning: Double = 0.1) =
        Insights(total, List(30) { 0f }, active, streak, 1, busiest, 12, night, morning)

    private fun facts(i: Insights, songs: Int = 20, artists: Int = 10, topPlays: Int = 4, share: Double = 0.1, likes: Int = 3) =
        MonthlyFacts(i, songs, artists, topPlays, share, likes)

    @Test fun personaFollowsHabits() {
        assertEquals("newcomer", MonthlyAchievements.persona(facts(insights(total = 10))).id)
        assertEquals("owl", MonthlyAchievements.persona(facts(insights(night = 0.6))).id)
        assertEquals("early", MonthlyAchievements.persona(facts(insights(morning = 0.5))).id)
        assertEquals("loyal", MonthlyAchievements.persona(facts(insights(), share = 0.5)).id)
        assertEquals("explorer", MonthlyAchievements.persona(facts(insights(), artists = 60)).id)
        assertEquals("marathon", MonthlyAchievements.persona(facts(insights(busiest = 300))).id)
        assertEquals("easy", MonthlyAchievements.persona(facts(insights())).id)
    }

    @Test fun achievementsProgressAndEarned() {
        val a = MonthlyAchievements.evaluate(facts(insights(streak = 7, active = 10, busiest = 90), likes = 15, topPlays = 4)).associateBy { it.id }
        assertTrue(a.getValue("streak").earned)
        assertTrue(a.getValue("hearts").earned)
        assertFalse(a.getValue("regular").earned)
        assertEquals(0.5f, a.getValue("regular").progress, 0.001f)
        assertEquals(0.4f, a.getValue("repeat").progress, 0.001f)
        assertEquals(0.5f, a.getValue("marathon").progress, 0.001f)
        assertTrue(a.values.all { it.progress in 0f..1f })
    }

    // ------------------------------------------------------------ Suasana

    @Test fun moodFitRanksTitles() {
        assertTrue(SmartMood.FOCUS.fit("Lofi Study Beats - Piano Instrumental", 200) >= 0.9)
        assertTrue(SmartMood.WORKOUT.fit("Chill Acoustic Lofi Vibes", 200) <= SmartMood.HARD_REJECT)
        assertTrue(SmartMood.SLEEP.fit("Heavy Rock Remix Live", 200) <= SmartMood.HARD_REJECT)
        assertTrue(SmartMood.WORKOUT.fit("Gym Phonk Bass Boosted", 200) > 0.4)
        assertEquals(0.0, SmartMood.CHILL.fit("Judul Biasa Saja", 200), 0.0)
        assertTrue(SmartMood.SLEEP.fit("Soft Piano", 30) < SmartMood.SLEEP.fit("Soft Piano", 300))
        assertEquals(SmartMood.SLEEP, SmartMood.byId("sleep"))
        assertNull(SmartMood.byId(""))
    }

    @Test fun moodFiltersAndPrefersMatchingSongs() {
        fun cand(id: String, title: String, artist: String) = Candidate(Song(id, title, artist, durationSec = 200), 0.5, null)
        val pool = listOf(
            cand("a", "Gym Phonk Bass Boosted", "Artis A"),
            cand("b", "Soft Lullaby Piano to Sleep", "Artis B"),
            cand("c", "Lagu Biasa Saja", "Artis C"),
            cand("d", "Workout Power Remix", "Artis D"),
        )
        val ctx = PickContext(emptySet(), emptyList(), 0.4, emptyMap(), mood = SmartMood.WORKOUT)
        val chosen = SmartScorer.choose(pool, TasteProfile.EMPTY, ctx, 3, Random(1))
        assertEquals(3, chosen.size)
        assertFalse(chosen.any { it.id == "b" })
        assertTrue(chosen.filter { it.id == "a" || it.id == "d" }.all { it.smartWhy!!.contains("Olahraga") })
    }

    // ------------------------------------------------------------ Unduhan otomatis

    @Test fun autoDownloadPrefersLikedAndFrequent() {
        val now = 1_800_000_000_000L
        val day = 24 * 3600_000L
        val c = listOf(
            DlCandidate("rare", false, 2, now - 40 * day),
            DlCandidate("liked", true, 2, now - 10 * day),
            DlCandidate("often", false, 30, now - 1 * day),
        )
        val plan = AutoDownloadPlanner.plan(c, heldCount = 0, now = now, perRun = 2)
        assertEquals(listOf("often", "liked"), plan)
    }

    @Test fun autoDownloadRespectsQuota() {
        val c = List(20) { DlCandidate("s$it", true, it, 0) }
        assertEquals(5, AutoDownloadPlanner.plan(c, heldCount = 35, now = 0, limit = 40, perRun = 8).size)
        assertTrue(AutoDownloadPlanner.plan(c, heldCount = 40, now = 0, limit = 40).isEmpty())
        assertEquals(8, AutoDownloadPlanner.plan(c, heldCount = 0, now = 0).size)
    }

    @Test fun autoDownloadEvictsOnlyStaleUnliked() {
        val now = 1_800_000_000_000L
        val day = 24 * 3600_000L
        val held = listOf(
            DlHeld("old", false, now - 60 * day, now - 90 * day),
            DlHeld("oldLiked", true, now - 60 * day, now - 90 * day),
            DlHeld("recent", false, now - 5 * day, now - 90 * day),
            DlHeld("newNeverPlayed", false, 0, now - 3 * day),
        )
        assertEquals(listOf("old"), AutoDownloadPlanner.evict(held, now))
    }

    // ------------------------------------------------------------ Timer tidur

    @Test fun sleepTimerCountsSongs() {
        val t = SleepTimer()
        t.onTrackFinished()
        assertFalse(t.state.value.active)
        t.startSongs(3)
        assertTrue(t.state.value.active)
        assertFalse(t.state.value.stopAtEndOfTrack)
        t.onTrackFinished()
        assertEquals(2, t.state.value.songsLeft)
        t.onTrackFinished()
        // Tinggal lagu terakhir: pemutar diminta berhenti tepat di akhir lagu ini.
        assertTrue(t.state.value.stopAtEndOfTrack)
        t.onTrackFinished()
        assertEquals(1, t.state.value.songsLeft)
        t.onStoppedAtEnd()
        assertFalse(t.state.value.active)
    }

    @Test fun sleepTimerEndOfTrackAndExtend() {
        val t = SleepTimer()
        t.endOfTrack()
        assertTrue(t.state.value.stopAtEndOfTrack)
        t.onTrackFinished() // mis. ulangi-satu: tetap menunggu akhir lagu
        assertTrue(t.state.value.stopAtEndOfTrack)
        t.onStoppedAtEnd()
        assertFalse(t.state.value.active)
        t.start(10, now = 1_000_000L)
        assertEquals(1_000_000L + 600_000L, t.state.value.endsAt)
        assertTrue(t.extend(10))
        assertEquals(1_000_000L + 1_200_000L, t.state.value.endsAt)
        assertFalse(t.state.value.stopAtEndOfTrack)
        t.onStoppedAtEnd() // jeda biasa tidak membatalkan timer menit
        assertTrue(t.state.value.active)
        t.cancel()
        assertFalse(t.extend(10))
        assertFalse(t.state.value.active)
    }

    // ------------------------------------------------------------ Cadangan

    @Test fun backupRoundTrip() {
        val s1 = Song("a1", "Judul \"Satu\"", "Artis, Satu", artistUrl = "https://y/c/1", album = "Album", durationSec = 215, thumbnail = "https://i/1.jpg")
        val s2 = Song("b2", "Dua", "Artis Dua")
        val data = BackupData(
            playlists = listOf(BackupPlaylist("Favorit", "deskripsi", true, listOf(s1, s2))),
            liked = listOf(s2),
            artists = listOf(BackupArtist("https://y/c/1", "Artis Satu", null)),
        )
        val back = Backup.parse(Backup.toJson(data, exportedAt = 5))
        assertEquals(1, back.playlists.size)
        val p = back.playlists[0]
        assertEquals("Favorit", p.name)
        assertTrue(p.pinned)
        assertEquals(listOf("a1", "b2"), p.songs.map { it.id })
        assertEquals(s1, p.songs[0])
        assertNull(p.songs[1].album)
        assertEquals(listOf("b2"), back.liked.map { it.id })
        assertEquals("Artis Satu", back.artists[0].name)
        assertNull(back.artists[0].thumbnail)
    }

    @Test fun backupRejectsForeignFiles() {
        for (bad in listOf("bukan json", "{}", """{"format":"lain","version":1}""", """{"format":"freease-backup","version":99}""")) {
            try {
                Backup.parse(bad)
                fail("seharusnya ditolak: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun backupSkipsBrokenEntries() {
        val json = """{"format":"freease-backup","version":1,"liked":[{"id":""},{"id":"ok","title":"T","artist":"A"}],"playlists":[],"artists":[{"url":""}]}"""
        val d = Backup.parse(json)
        assertEquals(listOf("ok"), d.liked.map { it.id })
        assertTrue(d.artists.isEmpty())
    }

    // ------------------------------------------------------------ Data

    @Test fun dataUsageMonthKey() {
        val k = DataUsage.monthKey(Calendar.getInstance().apply { clear(); set(2026, 9, 22, 12, 0) }.timeInMillis)
        assertEquals("202610", k)
    }
}
