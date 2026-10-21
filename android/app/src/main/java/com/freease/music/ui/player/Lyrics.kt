package com.freease.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.ui.components.darken
import com.freease.music.ui.navigation.LocalActions

sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data class Ready(val lyrics: Lyrics) : LyricsLoad
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberLyrics(song: Song?): State<LyricsLoad> {
    val actions = LocalActions.current
    return produceState<LyricsLoad>(LyricsLoad.Loading, song?.id) {
        value = LyricsLoad.Loading
        if (song == null) return@produceState
        value = LyricsLoad.Ready(runCatching { actions.container.lyrics.get(song) }.getOrDefault(Lyrics(null, null)))
    }
}

fun currentLineIndex(lyrics: Lyrics, positionMs: Long): Int {
    val lines = lyrics.synced ?: return -1
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs + 250) idx = i else break
    }
    return idx
}

/** Kartu lirik di bawah player (seperti Spotify). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LyricsCard(song: Song, color: Color, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val load by rememberLyrics(song)
    val position by remember { actions.player.positionFlow(200) }.collectAsStateWithLifecycle(0L)
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
        when (val l = load) {
            LyricsLoad.Loading -> Text("Mencari lirik…", color = Color.White.copy(alpha = 0.6f))
            is LyricsLoad.Ready -> {
                val lyrics = l.lyrics
                if (lyrics.isEmpty) {
                    Text("Lirik belum tersedia untuk lagu ini.", color = Color.White.copy(alpha = 0.6f))
                } else if (lyrics.synced != null) {
                    val idx = currentLineIndex(lyrics, position)
                    val start = (idx - 1).coerceAtLeast(0)
                    lyrics.synced.drop(start).take(6).forEachIndexed { i, line ->
                        val active = start + i == idx
                        Text(
                            line.text.ifBlank { "♪" },
                            fontSize = 20.sp,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (active) Color.White else Color.Black.copy(alpha = 0.55f),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                } else {
                    Text(
                        lyrics.plain ?: "",
                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 7, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Lirik layar penuh dengan auto-scroll; ketuk baris untuk lompat ke bagian itu. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LyricsScreen(song: Song, color: Color, onClose: () -> Unit) {
    val actions = LocalActions.current
    BackHandler(onBack = onClose)
    val load by rememberLyrics(song)
    val position by remember { actions.player.positionFlow(150) }.collectAsStateWithLifecycle(0L)
    val bg by animateColorAsState(color, tween(600), label = "lyricsBg")
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Tutup", modifier = Modifier.padding(2.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
            Spacer(Modifier.padding(24.dp))
        }
        when (val l = load) {
            LyricsLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Mencari lirik…", color = Color.White.copy(alpha = 0.7f))
            }
            is LyricsLoad.Ready -> {
                val lyrics = l.lyrics
                when {
                    lyrics.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Lirik belum tersedia 🎵", style = MaterialTheme.typography.titleMedium)
                    }
                    lyrics.synced != null -> {
                        val lines = lyrics.synced
                        val idx by remember(lines) { derivedStateOf { currentLineIndex(lyrics, position) } }
                        LaunchedEffect(idx) {
                            if (idx >= 0) {
                                val offset = with(density) { -180.dp.roundToPx() }
                                listState.animateScrollToItem(idx, offset)
                            }
                        }
                        LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 200.dp, bottom = 320.dp)) {
                            itemsIndexed(lines) { i, line ->
                                val active = i == idx
                                val c by animateColorAsState(
                                    when {
                                        active -> Color.White
                                        i < idx -> Color.White.copy(alpha = 0.55f)
                                        else -> Color.Black.copy(alpha = 0.5f)
                                    },
                                    tween(300), label = "line",
                                )
                                Text(
                                    line.text.ifBlank { "♪" },
                                    fontSize = 28.sp,
                                    lineHeight = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = c,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { actions.player.seekTo(line.timeMs) }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(lyrics.plain ?: "", fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(24.dp))
                        Text("Lirik tidak tersinkron • ${lyrics.source}", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

