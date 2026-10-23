package com.freease.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.freease.music.data.alarm.Alarm
import com.freease.music.data.model.Song
import com.freease.music.data.recognize.RecognizedSong
import com.freease.music.ui.navigation.AppActions
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.screens.AlarmScreen
import com.freease.music.ui.screens.DailyMixRow
import com.freease.music.ui.screens.RecognizeScreen
import com.freease.music.ui.screens.WrappedScreen
import com.freease.music.ui.theme.FreeaseTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Render layar-layar fitur baru dengan data contoh. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class NewFeaturesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("shots.dir") ?: "build/shots"

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
    fun screens() {
        val app = ApplicationProvider.getApplicationContext<FreeaseApp>()
        val c = app.container
        val songs = listOf(
            Song("s1", "Hati-Hati di Jalan", "Tulus", durationSec = 243),
            Song("s2", "Bertaut", "Nadin Amizah", durationSec = 321),
            Song("s3", "Evaluasi", "Hindia", durationSec = 211),
            Song("s4", "Ditto", "NewJeans", durationSec = 186),
            Song("s5", "Interaksi", "Tulus", durationSec = 220),
            Song("s6", "Sekali Lagi", "Isyana Sarasvati", durationSec = 250),
        )
        runBlocking {
            songs.forEachIndexed { i, s -> repeat(12 - i * 2) { c.library.recordPlay(s, s.durationSec * 1000) } }
        }
        // Daily Mix tersimpan hari ini.
        val mixJson = JSONObject().put("day", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())).put(
            "mixes",
            JSONArray().apply {
                listOf(listOf("Tulus", "Nadin Amizah", "Hindia"), listOf("NewJeans", "IVE", "LE SSERAFIM"), listOf("Isyana Sarasvati", "Raisa")).forEach { a ->
                    put(
                        JSONObject().put("artists", JSONArray(a)).put(
                            "songs",
                            JSONArray().apply { songs.forEach { s -> put(JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("d", s.durationSec)) } },
                        ),
                    )
                }
            },
        )
        File(app.filesDir, "daily_mix.json").writeText(mixJson.toString())
        runBlocking { c.dailyMix.ensureFresh() }
        c.recognizeHistory.add(RecognizedSong("In The Mood", "Glenn Miller", null, "Forever", "1990"))
        c.recognizeHistory.add(RecognizedSong("Hati-Hati di Jalan", "Tulus", null))
        c.alarms.upsert(Alarm(101, 6, 30, setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY), "liked", "Lagu yang Disukai"))
        c.alarms.upsert(Alarm(102, 8, 0, setOf(Calendar.SATURDAY, Calendar.SUNDAY), "pl:1", "Pagi Santai", enabled = false))

        val mode = androidx.compose.runtime.mutableIntStateOf(-1)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        compose.setContent {
            FreeaseTheme(0, false) {
                val nav = rememberNavController()
                val ctx = LocalContext.current
                val actions = remember { AppActions(c, nav, ctx) }
                CompositionLocalProvider(LocalActions provides actions, LocalContentColor provides Color.White) {
                    val m = mode.intValue
                    key(m) {
                        when (m) {
                            in 0..7 -> WrappedScreen(year, startPage = m)
                            8 -> RecognizeScreen()
                            9 -> AlarmScreen()
                            10 -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                                val mixes by c.dailyMix.mixes.collectAsState()
                                DailyMixRow(mixes)
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
        val names = (1..8).map { "wrapped_$it" } + listOf("recognize", "alarm", "dailymix")
        for (i in names.indices) {
            compose.runOnUiThread { mode.intValue = i }
            settle(2_500)
            compose.onRoot().captureRoboImage("$out/new_${names[i]}.png")
        }
    }
}
