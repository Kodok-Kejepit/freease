package com.freease.music.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.freease.music.data.model.Thumbs
import com.freease.music.playback.PlayerState
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.darken
import com.freease.music.ui.components.rememberArtworkColor
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onCollapse: () -> Unit) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val song = state.song ?: return
    val settings by actions.container.settings.flow.collectAsState()
    val videoMode by actions.container.videoMode.collectAsState()
    val color by rememberArtworkColor(if (settings.dynamicColor) song.artworkSmall else null, Freease.accent.primary.darken(0.55f))
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var canvasReady by remember { mutableStateOf(false) }
    val sleep by actions.container.sleepTimer.state.collectAsState()

    BackHandler(enabled = !fullscreen && !actions.showLyrics) { onCollapse() }

    if (fullscreen) {
        FullscreenVideo(onExit = { fullscreen = false })
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.verticalGradient(0f to color, 0.55f to color.darken(0.55f), 1f to Color(0xFF07070B)))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            if (!videoMode && !song.isLive && !song.isLocal) {
                CanvasBackground(song, state.isPlaying, Modifier.fillMaxWidth().height(pageHeight)) { canvasReady = it }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .height(pageHeight)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 22.dp),
                ) {
                    TopBar(onCollapse = onCollapse, onMore = { actions.showMenu(song) })
                    Spacer(Modifier.height(8.dp))
                    if (!song.isLive && !song.isLocal) {
                        ModeToggle(videoMode, onChange = { actions.container.videoMode.value = it }, Modifier.align(Alignment.CenterHorizontally))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if ((videoMode && !song.isLocal) || song.isLive) {
                            VideoSurface(state, onFullscreen = { fullscreen = true })
                        } else {
                            // Canvas tampil: sampul memudar tapi tetap bisa digeser untuk ganti lagu.
                            val artAlpha by animateFloatAsState(if (canvasReady) 0f else 1f, tween(600), label = "artAlpha")
                            Box(Modifier.alpha(artAlpha)) { SwipeArtwork(song, state) }
                        }
                    }
                    TitleRow(song)
                    Spacer(Modifier.height(8.dp))
                    SmartBar(song, settings.smartMode)
                    Spacer(Modifier.height(6.dp))
                    SeekSection(state.durationMs, song.isLive)
                    Spacer(Modifier.height(6.dp))
                    Controls(state)
                    Spacer(Modifier.height(10.dp))
                    BottomActions(
                        song = song,
                        sleepActive = sleep.active,
                        speedChanged = state.speed != 1f || state.pitch != 1f,
                        onSleep = { showSleep = true },
                        onSpeed = { showSpeed = true },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (settings.showLyricsCard) {
                    LyricsCard(song, color, onExpand = { actions.showLyrics = true }, Modifier.padding(horizontal = 16.dp))
                }
                Spacer(Modifier.height(16.dp))
                ArtistCard(song, color)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (showSleep) SleepTimerDialog { showSleep = false }
    if (showSpeed) SpeedDialog { showSpeed = false }
}

/** Tombol Freease Pintar; untuk lagu sisipan juga menampilkan alasan dan jempol naik/turun. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SmartBar(song: Song, enabled: Boolean) {
    val actions = LocalActions.current
    val settings by actions.container.settings.flow.collectAsState()
    var showMood by remember { mutableStateOf(false) }
    val mood = com.freease.music.playback.smart.SmartMood.byId(settings.smartMood)
    val accent = Freease.accent.primary
    val dim = Color.White.copy(alpha = 0.8f)
    if (showMood) MoodDialog { showMood = false }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (enabled) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
                    .clickable {
                        actions.container.settings.setSmartMode(!enabled)
                        actions.toast(if (enabled) "Freease Pintar dimatikan" else "Freease Pintar aktif")
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = if (enabled) accent else dim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Freease Pintar", style = MaterialTheme.typography.labelLarge, color = if (enabled) accent else dim)
            }
            if (enabled) {
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (mood != null) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
                        .clickable { showMood = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        mood?.label ?: "Suasana",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (mood != null) accent else dim,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (song.isSmartPick) {
                IconButton(onClick = {
                    actions.container.smart.thumbDown(song)
                    actions.toast("Tidak akan ditawarkan lagi")
                    actions.player.next()
                }) { Icon(Icons.Rounded.ThumbDown, "Kurang cocok", tint = dim, modifier = Modifier.size(22.dp)) }
                IconButton(onClick = {
                    actions.container.smart.thumbUp(song)
                    actions.toast("Ditambahkan ke Lagu yang Disukai")
                }) { Icon(Icons.Rounded.ThumbUp, "Cocok", tint = dim, modifier = Modifier.size(22.dp)) }
            }
        }
        song.smartWhy?.let { why ->
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Satu-satunya bagian yang membaca posisi — jadi hanya ini yang digambar ulang tiap detik. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SeekSection(durationMs: Long, live: Boolean) {
    val actions = LocalActions.current
    val position by remember { actions.player.positionFlow(250) }.collectAsStateWithLifecycle(0L)
    val buffered by remember { actions.player.bufferedFlow() }.collectAsStateWithLifecycle(0L)
    SeekBar(position, durationMs, buffered, onSeek = actions.player::seekTo, live = live)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TopBar(onCollapse: () -> Unit, onMore: () -> Unit) {
    val from by LocalActions.current.player.playingFrom.collectAsState()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup", modifier = Modifier.size(30.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SEDANG DIPUTAR", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Text(
                from.ifBlank { "Freease" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Menu") }
    }
}

@Composable
private fun ModeToggle(video: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).padding(3.dp),
    ) {
        listOf(false to "Lagu", true to "Video").forEach { (v, label) ->
            val sel = v == video
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (sel) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 22.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/** Artwork besar; geser kiri/kanan untuk ganti lagu, mengecil sedikit saat dijeda. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SwipeArtwork(song: Song, state: PlayerState) {
    val actions = LocalActions.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (state.isPlaying || state.isBuffering) 1f else 0.9f, spring(dampingRatio = 0.6f), label = "artScale")
    AnimatedContent(
        targetState = song,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it.id },
        label = "artwork",
    ) { s ->
        Artwork(
            s.artworkUrl,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(scale)
                .graphicsLayer {
                    translationX = offset.value
                    rotationZ = offset.value / 60f
                }
                .shadow(28.dp, RoundedCornerShape(12.dp))
                .pointerInput(s.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offset.value
                            scope.launch {
                                when {
                                    v < -180 -> actions.player.next()
                                    v > 180 -> actions.player.previous()
                                }
                                offset.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        },
                    ) { _, d -> scope.launch { offset.snapTo(offset.value + d) } }
                },
            fallback = Thumbs.fallback(s.id),
            shape = RoundedCornerShape(12.dp),
            iconSize = 72.dp,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(state: PlayerState, onFullscreen: () -> Unit) {
    val actions = LocalActions.current
    val controller by actions.player.controller.collectAsState()
    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f)).shadow(24.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        PlayerViewHost(controller, useController = false, Modifier.fillMaxSize())
        if (!state.isVideo || state.isBuffering) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
        }
        IconButton(
            onClick = onFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
        ) { Icon(Icons.Rounded.Fullscreen, "Layar penuh", tint = Color.White) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerViewHost(player: Player?, useController: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(com.freease.music.R.layout.video_view, null) as PlayerView).apply {
                this.useController = useController
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = useController
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideo(onExit: () -> Unit) {
    val actions = LocalActions.current
    val controller by actions.player.controller.collectAsState()
    val activity = LocalContext.current as Activity
    BackHandler(onBack = onExit)
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerViewHost(controller, useController = true, Modifier.fillMaxSize())
        IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Keluar layar penuh", tint = Color.White)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TitleRow(song: Song) {
    val actions = LocalActions.current
    val liked by remember(song.id) { actions.library.isLiked(song.id) }.collectAsState(false)
    val likeScale by animateFloatAsState(if (liked) 1.15f else 1f, spring(dampingRatio = 0.35f), label = "like")
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2500),
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = song.artistUrl != null) { actions.openArtist(song.artistUrl) },
            )
        }
        IconButton(onClick = { actions.toggleLike(song) }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "Suka",
                tint = if (liked) Freease.accent.secondary else Color.White,
                modifier = Modifier.size(30.dp).scale(likeScale),
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Controls(state: PlayerState) {
    val actions = LocalActions.current
    val accent = Freease.accent
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        val settings by actions.container.settings.flow.collectAsState()
        val smartShuffle = state.shuffle && settings.smartShuffle
        // Mati → Acak → Smart Shuffle → mati.
        IconButton(onClick = {
            val c = actions.container
            when {
                !state.shuffle -> {
                    c.settings.setSmartShuffle(false)
                    actions.player.toggleShuffle()
                }
                !settings.smartShuffle -> {
                    c.settings.setSmartShuffle(true)
                    c.emitMessage("Smart Shuffle: lagu rekomendasi yang cocok dengan playlist ini diselipkan")
                }
                else -> {
                    c.settings.setSmartShuffle(false)
                    actions.player.toggleShuffle()
                }
            }
        }) {
            Box {
                Icon(
                    Icons.Rounded.Shuffle, if (smartShuffle) "Smart Shuffle" else "Acak",
                    tint = if (state.shuffle) accent.primary else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(26.dp),
                )
                if (smartShuffle) {
                    Icon(
                        Icons.Rounded.AutoAwesome, null, tint = accent.primary,
                        modifier = Modifier.size(12.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                    )
                }
            }
        }
        IconButton(onClick = actions.player::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "Sebelumnya", modifier = Modifier.size(42.dp))
        }
        Box(
            Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = actions.player::toggle),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isBuffering && state.playWhenReady) {
                CircularProgressIndicator(Modifier.size(34.dp), color = Color.Black, strokeWidth = 3.dp)
            } else {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Putar/Jeda",
                    tint = Color.Black,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        IconButton(onClick = actions.player::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, "Berikutnya", modifier = Modifier.size(42.dp))
        }
        IconButton(onClick = actions.player::cycleRepeat) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Ulangi",
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) accent.primary else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun BottomActions(
    song: Song,
    sleepActive: Boolean,
    speedChanged: Boolean,
    onSleep: () -> Unit,
    onSpeed: () -> Unit,
) {
    val actions = LocalActions.current
    val entity by remember(song.id) { actions.library.observeSong(song.id) }.collectAsState(null)
    val downloaded = entity?.downloadState == DownloadState.DONE
    val accent = Freease.accent.primary
    val dim = Color.White.copy(alpha = 0.8f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onSleep) { Icon(Icons.Rounded.Bedtime, "Timer tidur", tint = if (sleepActive) accent else dim) }
        IconButton(onClick = onSpeed) { Icon(Icons.Rounded.Speed, "Kecepatan", tint = if (speedChanged) accent else dim) }
        IconButton(onClick = { actions.showLyrics = true }) { Icon(Icons.Rounded.Lyrics, "Lirik", tint = dim) }
        val karaoke by actions.container.karaokeMode.collectAsState()
        IconButton(onClick = {
            val on = !karaoke
            actions.container.karaokeMode.value = on
            actions.toast(if (on) "Mode Karaoke: vokal dihilangkan — paling bagus untuk rekaman studio stereo" else "Mode Karaoke mati")
        }) { Icon(Icons.Rounded.MicExternalOn, "Mode Karaoke", tint = if (karaoke) accent else dim) }
        if (!song.isLive) {
            IconButton(onClick = {
                if (downloaded) actions.toast("Sudah tersedia offline")
                else { actions.container.downloads.download(listOf(song)); actions.toast("Mengunduh…") }
            }) {
                Icon(if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Unduh", tint = if (downloaded) accent else dim)
            }
        }
        IconButton(onClick = { actions.share(song) }) { Icon(Icons.Rounded.Share, "Bagikan", tint = dim) }
        IconButton(onClick = { actions.showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Antrean", tint = dim) }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ArtistCard(song: Song, color: Color) {
    val actions = LocalActions.current
    val url = song.artistUrl ?: return
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.darken(0.4f))
            .clickable { actions.openArtist(url) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tentang artis", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Text(song.artist, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text("Lihat lagu, album, dan playlist", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("Buka", style = MaterialTheme.typography.labelLarge) }
    }
}
