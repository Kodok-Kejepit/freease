package com.freease.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.freease.music.data.lyrics.LyricsRepository
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.ui.navigation.AppActions
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.player.LyricsCard
import com.freease.music.ui.player.LyricsScreen
import com.freease.music.ui.theme.FreeaseTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Render layar & kartu lirik dengan lirik contoh (tanpa jaringan/player). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class LyricsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("shots.dir") ?: "build/shots"

    private val words = listOf(
        "Perjalanan membawamu", "Bertemu denganku", "Ku bertemu kamu", "Sepertimu yang kucari",
        "", "Konon aku juga seperti yang kau cari", "Kukira kita asam dan garam",
        "Dan kita bertemu di belanga", "Kisah yang ternyata tak seindah itu", "Kau tak sanggup",
    )

    private fun seed(c: AppContainer, id: String, l: Lyrics) {
        val dir = File(ApplicationProvider.getApplicationContext<FreeaseApp>().filesDir, "lyrics3").apply { mkdirs() }
        File(dir, "$id.json").writeText(LyricsRepository.encode(l).toString())
    }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    @Test
    fun lyrics() {
        val c = ApplicationProvider.getApplicationContext<FreeaseApp>().container
        val lines = words.mapIndexed { i, w -> LyricLine(13_000L + i * 4_000L, w) }
        // Geseran -25 dtk: posisi 0 di tes = detik 25 lagu → baris ke-4 sedang dinyanyikan.
        val mid = Song("lyrMid", "Hati-Hati di Jalan", "Tulus", durationSec = 243)
        seed(c, mid.id, Lyrics(lines, null, "Sumber: LRCLIB", offsetMs = -25_000, userSynced = true))
        val intro = Song("lyrIntro", "Hati-Hati di Jalan", "Tulus", durationSec = 243)
        seed(c, intro.id, Lyrics(lines, null, "Sumber: LyricFind", autoSync = Lyrics.AutoSync.DONE, timingRefId = "ref"))
        // Lagu Korea: offset −26 dtk → posisi 0 = 1,15 dtk ke dalam baris ke-4 (karaoke terisi sebagian).
        val kor = Song("lyrKor", "APT.", "ROSÉ & Bruno Mars", durationSec = 170)
        val korLines = listOf("채영이가 좋아하는 랜덤게임", "랜덤게임 Game start", "아파트 아파트", "Kissy face, kissy face", "사랑해 너를 원해", "Uh, uh-huh, uh-huh")
            .mapIndexed { i, w -> LyricLine(13_000L + i * 4_000L, w) }
        seed(c, kor.id, Lyrics(korLines, null, "Sumber: LRCLIB", offsetMs = -26_000, userSynced = true))
        val tap = Song("lyrTap", "Hati-Hati di Jalan (Official Music Video)", "Tulus", durationSec = 300)
        seed(c, tap.id, Lyrics(lines, null, "Sumber: LRCLIB", timingTrusted = false, autoSync = Lyrics.AutoSync.NOT_MATCHING, timingRefId = "ref"))

        val mode = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            FreeaseTheme(0, false) {
                val nav = rememberNavController()
                val ctx = LocalContext.current
                val actions = remember { AppActions(c, nav, ctx) }
                CompositionLocalProvider(LocalActions provides actions, LocalContentColor provides Color.White) {
                    when (mode.intValue) {
                        0 -> LyricsScreen(mid, Color(0xFF7A3FB0), onClose = {})
                        1 -> LyricsScreen(intro, Color(0xFF2F6F8F), onClose = {})
                        2 -> LyricsScreen(tap, Color(0xFF8F4A2F), onClose = {})
                        4 -> LyricsScreen(kor, Color(0xFFB0306A), onClose = {})
                        3 -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 40.dp)) {
                            LyricsCard(mid, Color(0xFF7A3FB0), onExpand = {}, Modifier.padding(16.dp))
                        }
                        else -> Unit
                    }
                }
            }
        }
        val names = listOf("lyrics_1_following", "lyrics_2_intro", "lyrics_3_tap", "lyrics_4_card", "lyrics_5_korean")
        for (i in names.indices) {
            compose.runOnUiThread { mode.intValue = i }
            settle(2_500)
            compose.onRoot().captureRoboImage("$out/${names[i]}.png")
        }
        // Kartu lirik untuk dibagikan.
        val card = com.freease.music.ui.player.LyricsCardRenderer.render(
            mid, listOf("Ku bertemu kamu", "Sepertimu yang kucari", "Konon aku juga seperti yang kau cari"),
            com.freease.music.ui.player.CardStyle(0xFF7A3FB0.toInt(), 0xFF2A1240.toInt(), 0xFFFFFFFF.toInt()), null,
        )
        java.io.File(out).mkdirs()
        java.io.File(out, "lyrics_6_sharecard.png").outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
