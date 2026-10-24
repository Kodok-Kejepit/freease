package com.freease.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.ui.FreeaseRoot
import com.freease.music.ui.navigation.AppActions
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    private val container get() = (application as FreeaseApp).container
    private var actions: AppActions? = null
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingIntent = intent
        setContent {
            FreeaseRoot(container) { a ->
                actions = a
                pendingIntent?.let { handleIntent(it) }
                pendingIntent = null
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) container.touchInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        container.appVisible.value = true
        container.player.connect()
    }

    override fun onStop() {
        super.onStop()
        container.appVisible.value = false
        if (!isChangingConfigurations) container.player.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (actions != null) handleIntent(intent) else pendingIntent = intent
    }

    private fun handleIntent(intent: Intent) {
        val a = actions ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            a.playerExpanded = true
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_LYRICS, false)) {
            a.playerExpanded = true
            a.showLyrics = true
            return
        }
        intent.getStringExtra(EXTRA_PLAY_SOURCE)?.let { source ->
            intent.removeExtra(EXTRA_PLAY_SOURCE)
            playSource(a, source)
            return
        }
        if (intent.getBooleanExtra(EXTRA_RECOGNIZE, false)) {
            a.navigate(com.freease.music.ui.navigation.Routes.recognize(auto = true))
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_ALARMS, false)) {
            a.navigate(com.freease.music.ui.navigation.Routes.ALARMS)
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        when {
            YouTubeRepository.isYouTubeUrl(text) -> a.handleLink(text)
            com.freease.music.data.importer.SpotifyReader.isSpotifyLink(text) ->
                a.navigate(com.freease.music.ui.navigation.Routes.import(text.trim()))
        }
    }

    /** Pintasan widget "Lanjutkan": putar sumber lagu lalu buka player. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playSource(a: com.freease.music.ui.navigation.AppActions, source: String) {
        if (source == com.freease.music.widget.ContinueWidget.SOURCE_RESUME) {
            val c = a.player.controller.value
            if (c != null && c.mediaItemCount > 0) {
                c.play()
            } else {
                startService(
                    Intent(this, com.freease.music.playback.PlaybackService::class.java)
                        .setAction(com.freease.music.playback.PlaybackService.ACTION_WIDGET)
                        .putExtra(com.freease.music.playback.PlaybackService.EXTRA_CMD, "toggle"),
                )
            }
            a.playerExpanded = true
            return
        }
        lifecycleScope.launch {
            val (songs, label) = if (source.startsWith("mix:")) {
                container.dailyMix.ensureFresh()
                val mix = container.dailyMix.mixes.value.getOrNull(source.removePrefix("mix:").toIntOrNull() ?: 0)
                (mix?.songs.orEmpty()) to (mix?.title ?: "Daily Mix")
            } else {
                com.freease.music.playback.LibraryTree(container).songsOf(source) to when (source) {
                    com.freease.music.playback.LibraryTree.LIKED -> "Lagu yang Disukai"
                    com.freease.music.playback.LibraryTree.RECENT -> "Baru diputar"
                    com.freease.music.playback.LibraryTree.DOWNLOADS -> "Unduhan"
                    else -> "Playlist"
                }
            }
            if (songs.isEmpty()) {
                a.toast("Belum ada lagu di $label")
                return@launch
            }
            a.player.play(songs, shuffle = source == com.freease.music.playback.LibraryTree.LIKED, from = label)
            a.playerExpanded = true
        }
    }

    companion object {
        const val EXTRA_OPEN_LYRICS = "open_lyrics"
        const val EXTRA_PLAY_SOURCE = "play_source"
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_OPEN_ALARMS = "open_alarms"
        const val EXTRA_RECOGNIZE = "recognize"
    }
}
