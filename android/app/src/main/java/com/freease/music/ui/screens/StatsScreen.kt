package com.freease.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.db.DayStat
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.FilterChipRow
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val ranges = listOf("7 hari" to 7, "30 hari" to 30, "Semua" to 0)

/** "Rekap" ala Spotify Wrapped: total menit, grafik harian, artis & lagu teratas. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun StatsScreen() {
    val actions = LocalActions.current
    var range by rememberSaveable { mutableIntStateOf(0) }
    val days = ranges[range].second
    val since = remember(days) { if (days == 0) 0L else System.currentTimeMillis() - days * 86_400_000L }
    val offset = remember { TimeZone.getDefault().rawOffset.toLong() }
    val total by remember(since) { actions.library.totalMs(since) }.collectAsState(0L)
    val distinct by remember(since) { actions.library.distinctSongs(since) }.collectAsState(0)
    val topSongs by remember(since) { actions.library.topSongs(since, 10) }.collectAsState(emptyList())
    val topArtists by remember(since) { actions.library.topArtists(since, 5) }.collectAsState(emptyList())
    val chartSince = remember { System.currentTimeMillis() - 7 * 86_400_000L }
    val perDay by remember { actions.library.perDay(chartSince, offset) }.collectAsState(emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Freease.accent.primary.copy(alpha = 0.45f), Color.Transparent)))
                    .statusBarsPadding(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                    Text("Rekap Kamu", style = MaterialTheme.typography.titleLarge)
                }
                FilterChipRow(ranges.map { it.first }, range, { range = it })
                Spacer(Modifier.height(20.dp))
                // Angka utama (stat tile)
                Text(
                    "%,d".format(total / 60_000).replace(',', '.'),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "menit mendengarkan • $distinct lagu berbeda",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Freease.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        if (total == 0L) {
            item { EmptyState(Icons.Rounded.BarChart, "Belum ada data", "Putar beberapa lagu, rekapmu akan muncul di sini.") }
            return@LazyColumn
        }
        item {
            Text(
                "Menit per hari • 7 hari terakhir",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
            )
            DailyBars(perDay, offset, Modifier.padding(horizontal = 16.dp))
        }
        if (topArtists.isNotEmpty()) {
            item {
                Text("Artis teratas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 8.dp))
            }
            itemsIndexed(topArtists, key = { i, a -> "a_${a.artist}_$i" }) { i, a ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = a.artistUrl != null) { actions.openArtist(a.artistUrl) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.width(30.dp))
                    Artwork(Thumbs.small(a.sampleId, a.sampleThumb), Modifier.size(54.dp), shape = CircleShape)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.artist, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.ms / 60_000} menit • ${a.plays}× diputar", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                    }
                }
            }
        }
        if (topSongs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)) {
                    Text("Lagu teratas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(
                        "Putar semua",
                        style = MaterialTheme.typography.labelLarge,
                        color = Freease.accent.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            actions.player.play(topSongs.map { it.song.toSong() }, from = "Lagu teratas kamu")
                        }.padding(6.dp),
                    )
                }
            }
            itemsIndexed(topSongs, key = { _, s -> "s_${s.song.id}" }) { i, s ->
                val song = s.song.toSong()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { actions.player.play(topSongs.map { it.song.toSong() }, i, from = "Lagu teratas kamu") }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.width(30.dp))
                    Artwork(song.artworkSmall, Modifier.size(54.dp), fallback = Thumbs.fallback(song.id))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${song.artist} • ${s.plays}× • ${s.ms / 60_000} mnt", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Satu seri → satu warna aksen, tanpa legenda (judul sudah menamai seri). Batang tipis,
 * ujung atas membulat 4dp, jarak antarbatang lebar. Hanya batang tertinggi diberi label;
 * ketuk batang lain untuk melihat nilainya.
 */
@Composable
private fun DailyBars(stats: List<DayStat>, offset: Long, modifier: Modifier = Modifier) {
    val today = (System.currentTimeMillis() + offset) / 86_400_000L
    val dayKeys = (6 downTo 0).map { today - it }
    val minutes = dayKeys.map { d -> (stats.firstOrNull { it.day == d }?.ms ?: 0L) / 60_000f }
    val max = (minutes.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val maxIndex = minutes.indexOf(minutes.maxOrNull() ?: 0f)
    var selected by remember { mutableStateOf(maxIndex) }
    val fmt = remember { SimpleDateFormat("EEE", Locale("id")) }
    val labels = dayKeys.map { d -> fmt.format(d * 86_400_000L - offset + 43_200_000L) }
    val bar = Freease.accent.primary
    val grid = Freease.colors.divider

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(20.dp)) {
            val sel = selected.coerceIn(0, 6)
            Text(
                "${labels[sel]}: ${minutes[sel].toInt()} menit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> selected = ((o.x / size.width) * 7).toInt().coerceIn(0, 6) }
                },
        ) {
            val slot = size.width / 7
            val w = (slot * 0.42f).coerceAtMost(28.dp.toPx())
            val r = 4.dp.toPx()
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            minutes.forEachIndexed { i, m ->
                val h = (m / max) * (size.height - 8.dp.toPx())
                if (h <= 0f) return@forEachIndexed
                val x = slot * i + (slot - w) / 2
                val alpha = if (i == selected) 1f else 0.55f
                // Ujung atas membulat, dasar rata menempel garis dasar.
                drawRoundRect(bar.copy(alpha = alpha), Offset(x, size.height - h), Size(w, h), CornerRadius(r, r))
                val square = minOf(h, r)
                drawRect(bar.copy(alpha = alpha), Offset(x, size.height - square), Size(w, square))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else Freease.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
