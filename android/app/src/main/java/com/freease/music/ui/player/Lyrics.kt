package com.freease.music.ui.player

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.lyrics.LyricsState
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.ui.components.darken
import com.freease.music.ui.navigation.LocalActions

/** Status lirik [song], dibagi bersama kartu dan layar penuh (sumbernya satu: LyricsRepository). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberLyricsState(song: Song): State<LyricsState> {
    val repo = LocalActions.current.container.lyrics
    val flow = remember(song.id) { repo.state(song.id) }
    LaunchedEffect(song.id, song.title, song.artist, song.durationSec) { repo.ensure(song) }
    return flow.collectAsStateWithLifecycle()
}

/** Baris yang sedang dinyanyikan; [offsetMs] positif = lirik digeser lebih lambat. */
fun currentLineIndex(lyrics: Lyrics, positionMs: Long, offsetMs: Long = lyrics.offsetMs): Int {
    val lines = lyrics.synced ?: return -1
    val t = positionMs - offsetMs + LOOKAHEAD_MS
    var lo = 0
    var hi = lines.size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= t) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return idx
}

/**
 * Sorotan sedikit mendahului suara: posisi dibaca tiap 100 ms dan warna butuh ±150 ms untuk berubah,
 * jadi tanpa ini baris selalu terasa telat.
 */
private const val LOOKAHEAD_MS = 150L

/**
 * Posisi putar untuk lirik: dibaca ±25×/detik selama lagu berputar (MediaController menghitungnya
 * sendiri tanpa IPC, jadi murah), 5×/detik saat dijeda supaya seek tetap terlihat. Berhenti saat app
 * di latar belakang.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberLyricsPosition(active: Boolean): State<Long> {
    val player = LocalActions.current.player
    val source = remember(active) {
        if (!active) {
            flowOf(0L)
        } else {
            flow {
                while (true) {
                    emit(player.currentPosition())
                    delay(if (player.state.value.isPlaying) 40 else 200)
                }
            }.distinctUntilChanged()
        }
    }
    return source.collectAsStateWithLifecycle(0L)
}

/** Intro/jeda instrumental cukup panjang untuk diberi titik "• • •" seperti Spotify. */
private const val INTRO_DOTS_MS = 4_000L

private fun hasIntro(l: Lyrics): Boolean =
    (l.synced?.firstOrNull { it.text.isNotBlank() && it.text != "♪" }?.timeMs ?: 0L) + l.offsetMs > INTRO_DOTS_MS

/** Tiga titik yang terisi bertahap selama intro / jeda instrumental. */
@Composable
private fun InstrumentalDots(progress: Float, active: Boolean, modifier: Modifier = Modifier, dotSize: androidx.compose.ui.unit.Dp = 14.dp) {
    val alpha by animateFloatAsState(if (active) 1f else 0.45f, tween(250), label = "dotsAlpha")
    Canvas(modifier.size(width = dotSize * 4, height = dotSize)) {
        val r = size.height / 2
        for (i in 0 until 3) {
            val fill = ((progress * 3) - i).coerceIn(0f, 1f)
            val c = Color.White.copy(alpha = alpha * (0.35f + 0.65f * fill))
            drawCircle(c, radius = r * (0.8f + 0.2f * fill), center = androidx.compose.ui.geometry.Offset(r + i * (size.width - 2 * r) / 2, r))
        }
    }
}

@Composable
private fun SyncingLabel(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
        Spacer(Modifier.width(6.dp))
        Text("Menyelaraskan…", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}

private fun fmtOffset(ms: Long): String {
    val txt = String.format(java.util.Locale("id"), "%.2f", kotlin.math.abs(ms) / 1000.0).trimEnd('0').trimEnd(',')
    return when {
        ms > 0 -> "+$txt dtk"
        ms < 0 -> "-$txt dtk"
        else -> "Pas"
    }
}

private fun LyricLine.display(): String = text.ifBlank { "♪" }

/** Kartu lirik di bawah player (seperti Spotify): bergulir halus mengikuti lagu. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LyricsCard(song: Song, color: Color, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val state by rememberLyricsState(song)
    val dim = Color.White.copy(alpha = 0.65f)
    val ready = state as? LyricsState.Ready
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.darken(0.15f))
            .clickable(onClick = onExpand)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lirik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (ready?.syncing == true) SyncingLabel(Modifier.padding(end = 6.dp))
            Icon(Icons.Rounded.OpenInFull, "Perbesar", modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            LyricsState.Loading -> Text("Mencari lirik…", color = dim)
            LyricsState.Failed -> Text("Gagal memuat lirik. Periksa koneksi, lalu ketuk untuk coba lagi.", color = dim)
            is LyricsState.Ready -> {
                val l = s.lyrics
                when {
                    l.isEmpty -> Text("Lirik belum tersedia untuk lagu ini.", color = dim)
                    l.canFollow -> CardFollowing(l)
                    else -> {
                        Text(
                            l.text,
                            fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                            maxLines = 6, overflow = TextOverflow.Ellipsis,
                        )
                        if (l.synced != null && !s.syncing) {
                            Spacer(Modifier.height(10.dp))
                            Text("Ketuk untuk membuka dan menyelaraskan lirik.", style = MaterialTheme.typography.labelMedium, color = dim)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun CardFollowing(l: Lyrics) {
    val lines = l.synced ?: return
    val position by rememberLyricsPosition(active = true)
    val idx by remember(l) { derivedStateOf { currentLineIndex(l, position) } }
    val intro = hasIntro(l)
    val shift = if (intro) 1 else 0
    val listState = rememberLazyListState()
    LaunchedEffect(idx) {
        // Baris aktif jadi baris kedua di kartu; baris sebelumnya masih terlihat sedikit.
        listState.animateScrollToItem((idx + shift - 1).coerceAtLeast(0))
    }
    LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxWidth().height(196.dp)) {
        if (intro) {
            item {
                val first = lines.first { it.text.isNotBlank() && it.text != "♪" }.timeMs + l.offsetMs
                InstrumentalDots(
                    progress = (position.toFloat() / first).coerceIn(0f, 1f),
                    active = idx < 0,
                    modifier = Modifier.padding(vertical = 8.dp),
                    dotSize = 10.dp,
                )
            }
        }
        itemsIndexed(lines) { i, line ->
            val c by animateColorAsState(
                when {
                    i == idx -> Color.White
                    i < idx -> Color.White.copy(alpha = 0.55f)
                    else -> Color.Black.copy(alpha = 0.55f)
                },
                tween(200), label = "cardLine",
            )
            if (line.text.isBlank() || line.text == "♪") {
                InstrumentalDots(if (i < idx) 1f else 0f, i == idx, Modifier.padding(vertical = 8.dp), dotSize = 10.dp)
            } else {
                Text(
                    line.display(),
                    fontSize = 20.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

/** Lirik layar penuh. Ketuk baris untuk lompat; untuk versi video, ketuk baris yang sedang dinyanyikan untuk menyelaraskan. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LyricsScreen(song: Song, color: Color, onClose: () -> Unit) {
    val actions = LocalActions.current
    val repo = actions.container.lyrics
    BackHandler(onBack = onClose)
    val state by rememberLyricsState(song)
    val bg by animateColorAsState(color, tween(600), label = "lyricsBg")
    var showSync by remember(song.id) { mutableStateOf(false) }
    val ready = (state as? LyricsState.Ready)?.lyrics

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup", modifier = Modifier.padding(2.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
            if (ready?.synced != null) {
                IconButton(onClick = { showSync = !showSync }) { Icon(Icons.Rounded.Tune, "Atur sinkron lirik", modifier = Modifier.padding(2.dp)) }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
        when (val s = state) {
            LyricsState.Loading -> Message("Mencari lirik…")
            LyricsState.Failed -> Message("Gagal memuat lirik", "Periksa koneksi internet, lalu coba lagi.", "Coba lagi") { repo.retry(song) }
            is LyricsState.Ready -> {
                val l = s.lyrics
                when {
                    l.isEmpty -> Message("Lirik belum tersedia", "Belum ada lirik yang cocok untuk lagu ini.", "Cari ulang") { repo.refresh(song) }
                    l.synced != null -> SyncedLyrics(song, l, showSync, syncing = s.syncing)
                    else -> PlainLyrics(l)
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, detail: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.padding(end = 6.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun PlainLyrics(l: Lyrics) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(l.text, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        Text(
            listOfNotNull("Lirik tidak tersinkron", l.source.takeIf { it.isNotBlank() }).joinToString(" · "),
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SyncedLyrics(song: Song, l: Lyrics, showSync: Boolean, syncing: Boolean) {
    val actions = LocalActions.current
    val repo = actions.container.lyrics
    val ctx = LocalContext.current
    val lines = l.synced ?: return
    val following = l.canFollow

    when {
        syncing -> Banner(
            icon = { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) },
            text = if (following) {
                "Menyelaraskan lirik dengan suara video… sementara memakai waktu perkiraan."
            } else {
                "Menyelaraskan lirik dengan suara video… sebentar lagi lirik berjalan sendiri."
            },
        )
        !following -> Banner(
            icon = { Icon(Icons.Rounded.Sync, null) },
            text = "Versi video ini berbeda dengan versi lagunya. Ketuk baris tepat saat mulai dinyanyikan, " +
                "lirik langsung mengikuti.",
        )
    }
    if (showSync) {
        SyncPanel(
            offsetMs = l.offsetMs,
            following = following,
            onShift = { delta -> repo.setSync(song.id, (l.offsetMs + delta).coerceIn(-MAX_OFFSET, MAX_OFFSET), userSynced = true) },
            onReset = { repo.setSync(song.id, 0L, userSynced = false) },
            onRefresh = { repo.refresh(song) },
        )
    }

    val listState = rememberLazyListState()
    var lastDrag by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) lastDrag = SystemClock.uptimeMillis() }
    }
    val position by rememberLyricsPosition(active = following)
    val idx by remember(l, following) { derivedStateOf { if (following) currentLineIndex(l, position) else -1 } }
    val intro = following && hasIntro(l)
    val shift = if (intro) 1 else 0
    LaunchedEffect(idx, intro) {
        // Baris aktif dijaga di sekitar sepertiga atas layar. Jangan merebut gulir kalau pengguna baru
        // saja menggulir sendiri (mis. membaca baris berikutnya).
        if (!following || SystemClock.uptimeMillis() - lastDrag < 3_500) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem((idx + shift).coerceAtLeast(0), -(viewport * 0.3f).toInt())
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 360.dp)) {
        if (intro) {
            item {
                val first = lines.first { it.text.isNotBlank() && it.text != "♪" }.timeMs + l.offsetMs
                InstrumentalDots(
                    progress = (position.toFloat() / first).coerceIn(0f, 1f),
                    active = idx < 0,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
        itemsIndexed(lines) { i, line ->
            val active = i == idx
            val c by animateColorAsState(
                when {
                    !following -> Color.White.copy(alpha = 0.9f)
                    active -> Color.White
                    i < idx -> Color.White.copy(alpha = 0.55f)
                    else -> Color.Black.copy(alpha = 0.5f)
                },
                tween(180, easing = FastOutSlowInEasing), label = "line",
            )
            val scale by animateFloatAsState(if (following && !active) 0.96f else 1f, tween(250), label = "lineScale")
            val modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = {
                        if (following) {
                            actions.player.seekTo((line.timeMs + l.offsetMs).coerceAtLeast(0))
                            lastDrag = 0L
                        } else if (line.text.isNotBlank() && line.text != "♪") {
                            // Baris ini baru mulai dinyanyikan → selisihnya jadi geseran sinkron
                            // (dikurangi waktu reaksi jari yang selalu sedikit terlambat).
                            val offset = (actions.player.currentPosition() - line.timeMs - TAP_REACTION_MS)
                                .coerceIn(-MAX_OFFSET, MAX_OFFSET)
                            repo.setSync(song.id, offset, userSynced = true)
                        }
                    },
                    onLongClick = {
                        if (line.text.isNotBlank()) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "\"${line.text}\"\n${song.title} - ${song.artist}")
                            ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
                .padding(vertical = 8.dp)
            if (line.text.isBlank() || line.text == "♪") {
                // Jeda instrumental di tengah lagu.
                val next = lines.getOrNull(i + 1)?.timeMs?.plus(l.offsetMs)
                val start = line.timeMs + l.offsetMs
                val progress = if (active && next != null && next > start) ((position - start).toFloat() / (next - start)).coerceIn(0f, 1f) else if (i < idx) 1f else 0f
                Box(modifier) { InstrumentalDots(progress, active) }
            } else {
                Text(line.text, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = c, modifier = modifier)
            }
        }
        if (l.source.isNotBlank()) {
            item {
                val extra = when {
                    l.autoSync == Lyrics.AutoSync.DONE -> " · diselaraskan otomatis dengan video"
                    else -> ""
                }
                Text(l.source + extra, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun Banner(icon: @Composable () -> Unit, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 10.dp)) { icon() }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SyncPanel(offsetMs: Long, following: Boolean, onShift: (Long) -> Unit, onReset: () -> Unit, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(vertical = 4.dp),
    ) {
        if (following) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onShift(-250) }) { Icon(Icons.Rounded.Remove, "Lirik lebih cepat") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtOffset(offsetMs), fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Lirik terlalu cepat? Ketuk +. Terlambat? Ketuk −.",
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                IconButton(onClick = { onShift(250) }) { Icon(Icons.Rounded.Add, "Lirik lebih lambat") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            if (offsetMs != 0L) TextButton(onClick = onReset) { Text("Atur ulang") }
            TextButton(onClick = onRefresh) { Text("Cari ulang lirik") }
        }
    }
}

/** Rata-rata keterlambatan jari saat mengetuk mengikuti suara. */
private const val TAP_REACTION_MS = 250L

/** Geseran maksimum (intro video klip bisa lebih dari satu menit). */
private const val MAX_OFFSET = 10 * 60_000L
