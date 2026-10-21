package com.freease.music

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.freease.music.ui.FreeaseRoot
import com.freease.music.ui.navigation.AppActions
import com.freease.music.ui.navigation.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render layar asli (data YouTube sungguhan) ke PNG tanpa emulator.
 * Jalankan: ./gradlew :app:testDebugUnitTest --tests "*ScreenshotTest*" -Proborazzi.test.record=true
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("shots.dir") ?: "build/shots"

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(250)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(100)
        }
        compose.waitForIdle()
    }

    @Test
    fun screens() {
        val app = ApplicationProvider.getApplicationContext<FreeaseApp>()
        val c = app.container
        var actions: AppActions? = null
        // Data lokal contoh supaya Beranda/Koleksi tidak kosong.
        runBlocking {
            val songs = c.youtube.searchSongs("tulus", 8)
            songs.take(5).forEachIndexed { i, s -> c.library.recordPlay(s, 60_000L * (i + 2)) }
            songs.take(3).forEach { c.library.setLiked(it, true) }
            val pid = c.library.createPlaylist("Santai Sore")
            c.library.addToPlaylist(pid, songs.drop(2).take(5))
            c.library.createPlaylist("Galau Akut")
        }
        compose.setContent { FreeaseRoot(c) { actions = it } }
        settle(9_000)
        compose.onRoot().captureRoboImage("$out/01_home.png")

        compose.runOnUiThread { actions!!.navigate(Routes.SEARCH) }
        settle(1_500)
        compose.onRoot().captureRoboImage("$out/02_search_browse.png")

        compose.runOnUiThread { actions!!.navigate(Routes.LIBRARY) }
        settle(1_500)
        compose.onRoot().captureRoboImage("$out/03_library.png")

        compose.runOnUiThread { actions!!.openCategory("Lo-fi", "lofi hip hop beats") }
        settle(8_000)
        compose.onRoot().captureRoboImage("$out/04_category.png")

        compose.runOnUiThread { actions!!.openArtist("https://www.youtube.com/channel/UCRggxhdYIz0zSvUgJmCWMGg") }
        settle(9_000)
        compose.onRoot().captureRoboImage("$out/05_artist.png")

        compose.runOnUiThread { actions!!.openPlaylist("https://www.youtube.com/playlist?list=PLzgVPDDV8B8dWJbuhdUcxgW0yLYTJ9-IQ") }
        settle(8_000)
        compose.onRoot().captureRoboImage("$out/06_playlist.png")

        compose.runOnUiThread { actions!!.navigate(Routes.LIKED) }
        settle(3_000)
        compose.onRoot().captureRoboImage("$out/07_liked.png")

        compose.runOnUiThread { actions!!.navigate(Routes.STATS) }
        settle(2_000)
        compose.onRoot().captureRoboImage("$out/08_stats.png")

        compose.runOnUiThread { actions!!.navigate(Routes.SETTINGS) }
        settle(1_500)
        compose.onRoot().captureRoboImage("$out/09_settings.png")
    }
}
