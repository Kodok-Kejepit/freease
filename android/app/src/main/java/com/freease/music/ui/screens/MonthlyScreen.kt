package com.freease.music.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Thumbs
import com.freease.music.data.monthly.Achievement
import com.freease.music.data.monthly.MonthKey
import com.freease.music.data.monthly.MonthlyReport
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.SmallLoading
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun personaIcon(id: String): ImageVector = when (id) {
    "owl" -> Icons.Rounded.Bedtime
    "early" -> Icons.Rounded.WbSunny
    "loyal" -> Icons.Rounded.Favorite
    "explorer" -> Icons.Rounded.Explore
    "marathon" -> Icons.Rounded.Bolt
    "newcomer" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Headphones
}

private fun achievementIcon(id: String): ImageVector = when (id) {
    "streak" -> Icons.Rounded.LocalFireDepartment
    "regular" -> Icons.Rounded.EventAvailable
    "marathon" -> Icons.Rounded.Bolt
    "hours" -> Icons.Rounded.Schedule
    "explorer" -> Icons.Rounded.Explore
    "variety" -> Icons.Rounded.Groups
    "repeat" -> Icons.Rounded.Repeat
    else -> Icons.Rounded.Favorite
}

/** Rekap sebulan: pencapaian, lagu & album paling sering diputar, dan lagu yang paling disukai. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MonthlyScreen() {
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = remember { MonthKey.of(System.currentTimeMillis()) }
    val nowIdx = now.year * 12 + now.month
    var idx by rememberSaveable { mutableIntStateOf(nowIdx) }
    var firstIdx by remember { mutableIntStateOf(nowIdx) }
    var report by remember { mutableStateOf<MonthlyReport?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val accentArgb = Freease.accent.primary.toArgb()

    LaunchedEffect(Unit) {
        val f = actions.library.firstMonth()
        firstIdx = f.year * 12 + f.month
    }
    LaunchedEffect(idx) {
        report = null
        report = actions.library.monthlyReport(MonthKey(idx / 12, idx % 12))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Freease.accent.primary.copy(alpha = 0.5f), Color.Transparent)))
                    .statusBarsPadding(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                    Text("Monthly Freease", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    val r = report
                    IconButton(
                        enabled = r != null && !r.isEmpty && !sharing,
                        onClick = {
                            val rep = r ?: return@IconButton
                            sharing = true
                            scope.launch {
                                val intent = withContext(Dispatchers.Default) {
                                    runCatching {
                                        MonthlyCard.shareIntent(context, rep, accentArgb)
                                    }.getOrNull()
                                }
                                sharing = false
                                if (intent != null) context.startActivity(intent) else actions.toast("Gagal membuat kartu")
                            }
                        },
                    ) { Icon(Icons.Rounded.Share, "Bagikan kartu") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = idx > firstIdx, onClick = { idx-- }) { Icon(Icons.Rounded.ChevronLeft, "Bulan sebelumnya") }
                    Text(
                        MonthKey(idx / 12, idx % 12).label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(enabled = idx < nowIdx, onClick = { idx++ }) { Icon(Icons.Rounded.ChevronRight, "Bulan berikutnya") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        val r = report
        if (r == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { SmallLoading() } }
            return@LazyColumn
        }
        if (r.isEmpty) {
            item {
                EmptyState(
                    Icons.Rounded.CalendarMonth, "Belum ada data bulan ini",
                    "Putar beberapa lagu, rekap bulananmu akan tersusun otomatis.",
                )
            }
            return@LazyColumn
        }

        // ---- kartu utama
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Freease.accent.brush).padding(22.dp),
            ) {
                Text(r.month.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("%,d".format(r.insights.totalMin).replace(',', '.'), fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("menit mendengarkan", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
                r.deltaPercent?.let { d ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            d > 0 -> "Naik $d% dari bulan lalu"
                            d < 0 -> "Turun ${-d}% dari bulan lalu"
                            else -> "Sama seperti bulan lalu"
                        },
                        style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(personaIcon(r.persona.id), null, tint = Color.White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(r.persona.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(r.persona.blurb, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }

        // ---- angka ringkas
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Lagu berbeda", "${r.distinctSongs}", Modifier.weight(1f))
                    StatTile("Artis berbeda", "${r.distinctArtists}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Hari aktif", "${r.insights.activeDays}", Modifier.weight(1f))
                    StatTile("Runtunan terpanjang", "${r.insights.longestStreak} hari", Modifier.weight(1f))
                }
            }
        }
        item {
            Text(
                "Paling sering jam ${"%02d".format(r.insights.peakHour)}.00 • hari tersibuk tanggal ${r.insights.busiestDay} (${r.insights.busiestDayMin} menit)",
                style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))
            MonthBars(r.insights.perDayMin, Modifier.padding(horizontal = 16.dp))
        }

        // ---- lagu paling sering diputar
        if (r.topSongs.isNotEmpty()) {
            item { SectionTitle("Lagu paling sering diputar") }
            r.topSongs.forEachIndexed { i, s ->
                item(key = "song_${s.song.id}") {
                    val song = s.song.toSong()
                    RankRow(
                        i + 1, song.artworkSmall, Thumbs.fallback(song.id), song.title,
                        "${song.artist} • ${s.plays}× diputar",
                        onClick = { actions.player.play(r.topSongs.map { it.song.toSong() }, i, from = "Monthly Freease") },
                    )
                }
            }
        }

        // ---- album paling sering diputar
        item { SectionTitle("Album paling sering diputar") }
        if (r.topAlbums.isEmpty()) {
            item {
                Text(
                    "Belum ada data album. Info album hanya tersedia untuk sebagian lagu.",
                    style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            r.topAlbums.forEachIndexed { i, a ->
                item(key = "album_${a.album}_${a.artist}") {
                    RankRow(
                        i + 1, Thumbs.small(a.sampleId, a.sampleThumb), Thumbs.fallback(a.sampleId), a.album,
                        "${a.artist} • ${a.plays}× diputar • ${a.tracks} lagu",
                        onClick = null,
                    )
                }
            }
        }

        // ---- paling disukai
        item { SectionTitle("Paling kamu sukai") }
        if (r.likedSongs.isEmpty()) {
            item {
                Text(
                    "Belum ada lagu yang kamu sukai bulan ini.",
                    style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            item {
                Text(
                    buildString {
                        append("${r.likedSongs.size} lagu disukai bulan ini")
                        r.likedArtists.firstOrNull()?.let { append(" • paling banyak dari ${it.artist} (${it.likes})") }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }
            r.likedSongs.take(5).forEachIndexed { i, song ->
                item(key = "like_${song.id}") {
                    RankRow(
                        i + 1, song.artworkSmall, Thumbs.fallback(song.id), song.title, song.artist,
                        onClick = { actions.player.play(r.likedSongs, i, from = "Disukai bulan ini") },
                    )
                }
            }
        }

        // ---- pencapaian
        item {
            val earned = r.achievements.count { it.earned }
            SectionTitle("Pencapaian • $earned dari ${r.achievements.size}")
        }
        r.achievements.forEach { a -> item(key = "ach_${a.id}") { AchievementRow(a) } }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp))
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Freease.colors.card).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
    }
}

@Composable
private fun RankRow(rank: Int, art: String, fallback: String, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.width(30.dp))
        Artwork(art, Modifier.size(54.dp), fallback = fallback)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement) {
    val accent = Freease.accent.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Freease.colors.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (a.earned) Freease.accent.brush else Brush.linearGradient(listOf(Freease.colors.elevated, Freease.colors.elevated))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (a.earned) achievementIcon(a.id) else Icons.Rounded.Lock, null,
                tint = if (a.earned) Color.White else Freease.colors.muted, modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(a.detail, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
            if (!a.earned) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Freease.colors.elevated)) {
                    Box(Modifier.fillMaxWidth(a.progress.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(accent))
                }
            }
        }
        if (a.earned) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.CheckCircle, "Tercapai", tint = accent)
        }
    }
}

/** Batang menit per hari sepanjang bulan; satu warna aksen, ujung atas membulat. */
@Composable
private fun MonthBars(minutes: List<Float>, modifier: Modifier = Modifier) {
    val bar = Freease.accent.primary
    val grid = Freease.colors.divider
    val max = (minutes.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val slot = size.width / minutes.size
            val w = (slot * 0.62f).coerceAtLeast(2f)
            val r = 3.dp.toPx().coerceAtMost(w / 2)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            minutes.forEachIndexed { i, m ->
                val h = (m / max) * (size.height - 6.dp.toPx())
                if (h <= 0f) return@forEachIndexed
                val x = slot * i + (slot - w) / 2
                drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(r, r))
                val square = minOf(h, r)
                drawRect(bar, Offset(x, size.height - square), Size(w, square))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = MaterialTheme.typography.labelSmall, color = Freease.colors.muted)
            Text("${minutes.size}", style = MaterialTheme.typography.labelSmall, color = Freease.colors.muted)
        }
    }
}
