package com.freease.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.recognize.MicListener
import com.freease.music.data.recognize.RecognizedSong
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface RecState {
    data object Idle : RecState
    data object Listening : RecState
    data class Found(val song: RecognizedSong) : RecState
    data class Message(val title: String, val text: String) : RecState
}

/** Kenali lagu yang sedang diputar di sekitarmu lewat mikrofon. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RecognizeScreen(autoStart: Boolean = false) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by actions.container.recognizeHistory.items.collectAsState()
    var state by remember { mutableStateOf<RecState>(RecState.Idle) }
    var level by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (job?.isActive == true) return
        // Suara dari Freease sendiri akan mengganggu; jeda dulu.
        actions.player.controller.value?.takeIf { it.isPlaying }?.pause()
        state = RecState.Listening
        progress = 0f
        job = scope.launch {
            val listener = MicListener(actions.container.recognizer)
            state = when (val r = listener.listen(onLevel = { level = it }, onProgress = { progress = it })) {
                is MicListener.Outcome.Found -> {
                    actions.container.recognizeHistory.add(r.song)
                    RecState.Found(r.song)
                }
                MicListener.Outcome.NotFound -> RecState.Message("Lagu tidak dikenali", "Dekatkan HP ke sumber suara dan coba lagi saat bagian lagunya lebih jelas.")
                is MicListener.Outcome.Failed -> RecState.Message("Gagal mendengarkan", r.message)
            }
            level = 0f
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else state = RecState.Message("Izin mikrofon dibutuhkan", "Freease hanya mendengarkan saat kamu menekan tombol, dan rekaman tidak disimpan.")
    }

    fun tap() {
        if (state == RecState.Listening) {
            job?.cancel()
            state = RecState.Idle
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { if (autoStart) tap() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { job?.cancel(); actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Kenali Lagu", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            ListenButton(listening = state == RecState.Listening, level = level, onClick = ::tap)
            Spacer(Modifier.height(20.dp))
            val (title, text) = when (val s = state) {
                RecState.Idle -> "Ketuk untuk mengenali lagu" to "Putar lagunya di dekat HP — dari radio, kafe, TV, mana saja."
                RecState.Listening -> "Mendengarkan…" to if (progress < 0.33f) "Biarkan lagunya terdengar beberapa detik" else "Mencari kecocokan…"
                is RecState.Found -> "" to ""
                is RecState.Message -> s.title to s.text
            }
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    text, style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }
            (state as? RecState.Found)?.let { ResultCard(it.song) { state = RecState.Idle } }
        }
        if (history.isNotEmpty()) {
            item {
                Text(
                    "Pernah dikenali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 28.dp, bottom = 6.dp),
                )
            }
            items(history, key = { "${it.title}|${it.artist}|${it.recognizedAt}" }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { playRecognized(actions, s) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(s.coverUrl, Modifier.size(48.dp), shape = RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.artist + " • " + com.freease.music.util.Format.relativeDay(s.recognizedAt),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                        )
                    }
                    IconButton(onClick = { actions.container.recognizeHistory.remove(s) }) {
                        Icon(Icons.Rounded.Close, "Hapus", tint = Freease.colors.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val accent = Freease.accent.primary
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val lvl by animateFloatAsState(if (listening) level else 0f, tween(120), label = "lvl")
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in 0 until 3) {
                    val f = (ring + k / 3f) % 1f
                    drawCircle(accent.copy(alpha = (1f - f) * 0.5f), radius = size.minDimension / 2 * (0.55f + 0.45f * f), style = Stroke(3.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.size((150 + 24 * lvl).dp).clip(CircleShape).background(accent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote, "Kenali lagu", tint = Color.Black, modifier = Modifier.size(72.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ResultCard(song: RecognizedSong, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    Column(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Freease.colors.card).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(song.coverUrl, Modifier.size(180.dp), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = Freease.colors.muted, textAlign = TextAlign.Center)
        listOfNotNull(song.album, song.year).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Freease.accent.primary).clickable { playRecognized(actions, song) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black)
                Spacer(Modifier.width(4.dp))
                Text("Putar", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.clip(CircleShape).background(Freease.colors.elevated).clickable {
                    actions.searchFor(song.searchQuery)
                }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null)
                Spacer(Modifier.width(4.dp))
                Text("Cari")
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Tutup") }
        }
    }
}

/** Cari versi YouTube Music dari lagu yang dikenali lalu putar sebagai radio. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun playRecognized(actions: com.freease.music.ui.navigation.AppActions, s: RecognizedSong) {
    actions.toast("Mencari \"${s.title}\"…")
    actions.container.appScope.launch {
        val found = runCatching { actions.container.youtube.searchSongs(s.searchQuery, 10) }.getOrDefault(emptyList())
        val best = found.firstOrNull { com.freease.music.data.lyrics.LyricsMatch.songMatches(it.title, it.artist, s.title, s.artist) }
            ?: found.firstOrNull()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (best == null) actions.toast("Lagu tidak ditemukan di YouTube Music") else actions.player.playSingle(best, "Kenali Lagu")
        }
    }
}
