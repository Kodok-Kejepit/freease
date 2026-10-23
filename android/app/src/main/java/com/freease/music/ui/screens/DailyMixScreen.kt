package com.freease.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freease.music.data.mix.DailyMix
import com.freease.music.ui.components.CollectionScreen
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.HeaderIcon
import com.freease.music.ui.components.MosaicCover
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.Routes
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.launch

private val MixColors = listOf(Color(0xFF1ED760), Color(0xFFFF6437), Color(0xFF4F9DFF), Color(0xFFE8115B), Color(0xFFB49BC8))

fun mixColor(index: Int) = MixColors[index % MixColors.size]

/** Sampul Daily Mix: mozaik sampul + pita warna khas tiap mix. */
@Composable
fun DailyMixCover(mix: DailyMix, modifier: Modifier = Modifier) {
    val color = mixColor(mix.index)
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        MosaicCover(mix.songs.take(4).map { it.id }, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Box(Modifier.fillMaxWidth().height(5.dp).background(color).align(Alignment.BottomCenter))
        Text(
            mix.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp),
        )
    }
}

@Composable
fun DailyMixRow(mixes: List<DailyMix>, size: Dp = 150.dp) {
    val actions = LocalActions.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(mixes, key = { it.index }) { m ->
            Column(Modifier.width(size).clip(RoundedCornerShape(10.dp)).clickable { actions.navigate(Routes.mix(m.index)) }) {
                DailyMixCover(m, Modifier.size(size))
                Spacer(Modifier.height(8.dp))
                Text(
                    m.subtitle, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun DailyMixScreen(index: Int) {
    val actions = LocalActions.current
    val mixes by actions.container.dailyMix.mixes.collectAsState()
    val scope = rememberCoroutineScope()
    val mix = mixes.getOrNull(index)
    if (mix == null) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(Icons.Rounded.Refresh, "Mix belum tersedia", "Dengarkan lebih banyak lagu — Daily Mix dibuat dari kebiasaan dengarmu.")
        }
        return
    }
    CollectionScreen(
        title = mix.title,
        subtitle = "${mix.subtitle}\n${mix.songs.size} lagu • ${Format.longDuration(mix.songs.sumOf { it.durationSec })} • diperbarui tiap hari",
        from = mix.title,
        songs = mix.songs,
        colorUrl = mix.songs.firstOrNull()?.artworkSmall,
        cover = { m -> DailyMixCover(mix, m) },
        headerActions = {
            HeaderIcon(Icons.Rounded.Download, "Unduh semua") {
                actions.container.downloads.download(mix.songs)
                actions.toast("Mengunduh ${mix.songs.size} lagu")
            }
            HeaderIcon(Icons.Rounded.Refresh, "Buat ulang") {
                scope.launch {
                    actions.toast("Meracik ulang mix…")
                    actions.container.dailyMix.ensureFresh(force = true)
                }
            }
        },
    )
}
