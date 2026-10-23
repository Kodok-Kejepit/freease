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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

private fun fmtOffset(ms: Long): String {
    val txt = String.format(java.util.Locale("id"), "%.2f", kotlin.math.abs(ms) / 1000.0).trimEnd('0').trimEnd(',')
    return when {
        ms > 0 -> "+$txt dtk"
        ms < 0 -> "-$txt dtk"
        else -> "Pas"
    }
}

private fun LyricLine.display(): String = text.ifBlank { "♪" }

/** Kartu lirik di bawah player (seperti Spotify). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LyricsCard(song: Song, color: Color, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val state by rememberLyricsState(song)
    val dim = Color.White.copy(alpha = 0.65f)
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
                        if (l.synced != null) {
                            Spacer(Modifier.height(10.dp))
                            Text("Versi video ini beda dengan versi lagunya. Buka lirik untuk menyelaraskan.", style = MaterialTheme.typography.labelMedium, color = dim)
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
    val actions = LocalActions.current
    val lines = l.synced ?: return
    val position by remember { actions.player.positionFlow(200) }.collectAsStateWithLifecycle(0L)
    val idx = currentLineIndex(l, position)
    val start = (idx - 1).coerceAtLeast(0)
    lines.drop(start).take(6).forEachIndexed { i, line ->
        Text(
            line.display(),
            fontSize = 20.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (start + i == idx) Color.White else Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.padding(vertical = 3.dp),
        )
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
                    l.synced != null -> SyncedLyrics(song, l, showSync)
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
private fun SyncedLyrics(song: Song, l: Lyrics, showSync: Boolean) {
    val actions = LocalActions.current
    val repo = actions.container.lyrics
    val ctx = LocalContext.current
    val lines = l.synced ?: return
    val following = l.canFollow

    if (!following) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Sync, null, modifier = Modifier.padding(end = 10.dp))
            Text(
                "Waktu lirik ini untuk versi lagunya, sedangkan yang diputar versi video. " +
                    "Ketuk sebuah baris tepat saat baris itu mulai dinyanyikan, lirik akan langsung mengikuti.",
                style = MaterialTheme.typography.labelLarge,
            )
        }
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
    val density = LocalDensity.current
    var lastDrag by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) lastDrag = SystemClock.uptimeMillis() }
    }
    val position by remember(following) {
        if (following) actions.player.positionFlow(100) else kotlinx.coroutines.flow.flowOf(0L)
    }.collectAsStateWithLifecycle(0L)
    val idx by remember(l, following) { derivedStateOf { if (following) currentLineIndex(l, position) else -1 } }
    LaunchedEffect(idx) {
        // Jangan merebut gulir saat pengguna baru saja menggulir sendiri.
        if (idx >= 0 && SystemClock.uptimeMillis() - lastDrag > 3_500) {
            listState.animateScrollToItem(idx, with(density) { -180.dp.roundToPx() })
        }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 200.dp, bottom = 320.dp)) {
        itemsIndexed(lines) { i, line ->
            val active = i == idx
            val c by animateColorAsState(
                when {
                    !following -> Color.White.copy(alpha = 0.9f)
                    active -> Color.White
                    i < idx -> Color.White.copy(alpha = 0.55f)
                    else -> Color.Black.copy(alpha = 0.5f)
                },
                tween(150), label = "line",
            )
            val scale by animateFloatAsState(if (following && !active) 0.96f else 1f, tween(250), label = "lineScale")
            Text(
                line.display(),
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = c,
                modifier = Modifier
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
                    .padding(vertical = 8.dp),
            )
        }
        if (l.source.isNotBlank()) {
            item {
                Text(l.source, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 24.dp))
            }
        }
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
