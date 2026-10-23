package com.freease.music.ui.player

import android.view.LayoutInflater
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.freease.music.data.model.Song
import com.freease.music.playback.CanvasClip
import com.freease.music.playback.FreeaseMediaSourceFactory
import com.freease.music.playback.MediaItems
import com.freease.music.playback.StreamResolver
import com.freease.music.ui.navigation.LocalActions

/**
 * Canvas: potongan video ±8 detik (360p, tanpa suara) yang berulang di belakang player.
 * [onReady] = true saat frame pertama tampil, supaya sampul bisa disembunyikan.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CanvasBackground(song: Song, playing: Boolean, modifier: Modifier = Modifier, onReady: (Boolean) -> Unit) {
    val actions = LocalActions.current
    val c = actions.container
    val context = LocalContext.current
    val settings by c.settings.flow.collectAsState()
    var clip by remember(song.id) { mutableStateOf<CanvasClip?>(null) }
    var ready by remember(song.id) { mutableStateOf(false) }

    val allowed = when (settings.canvasMode) {
        0 -> false
        1 -> !c.isMetered()
        else -> true
    }
    LaunchedEffect(song.id, allowed) {
        onReady(false)
        clip = if (allowed) c.canvas.resolve(song) else null
    }
    val current = clip ?: return

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(FreeaseMediaSourceFactory(context, c.mediaCache, c.streamClient, StreamResolver(c.youtube)))
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
                onReady(true)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                ready = false
                onReady(false)
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
            onReady(false)
        }
    }
    LaunchedEffect(current) {
        ready = false
        val item = MediaItem.Builder()
            .setMediaId("${current.videoId}#canvas")
            .setUri(MediaItems.videoUri(current.videoId, 360))
            .setCustomCacheKey(MediaItems.videoKey(current.videoId, 360))
            .build()
        val source = FreeaseMediaSourceFactory(context, c.mediaCache, c.streamClient, StreamResolver(c.youtube)).createMediaSource(item)
        player.setMediaSource(ClippingMediaSource(source, current.startMs * 1000, (current.startMs + current.lengthMs) * 1000))
        player.prepare()
    }
    LaunchedEffect(playing) { player.playWhenReady = playing }

    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(600), label = "canvas")
    Box(modifier.alpha(alpha)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(com.freease.music.R.layout.video_view, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Gelapkan atas & bawah supaya judul dan tombol tetap terbaca.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.25f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.85f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
        )
    }
}
