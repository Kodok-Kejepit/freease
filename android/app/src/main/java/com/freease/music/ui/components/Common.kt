package com.freease.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freease.music.ui.theme.Freease
import com.freease.music.ui.theme.SectionTitleStyle

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Freease.colors.muted)
            }
            Text(title, style = SectionTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, color = Freease.colors.muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Tiga batang equalizer — penanda lagu yang sedang diputar. Hanya beranimasi saat musik
 * berputar, dan nilainya dibaca di fase gambar (tidak memicu recomposition tiap frame).
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean = true, color: Color = Freease.accent.primary) {
    val levels = remember { List(3) { Animatable(0.35f) } }
    LaunchedEffect(playing) {
        if (!playing) {
            levels.forEach { launch { it.animateTo(0.3f, tween(200)) } }
            return@LaunchedEffect
        }
        listOf(420, 300, 520).forEachIndexed { i, d ->
            launch {
                delay(i * 60L)
                while (true) {
                    levels[i].animateTo(1f, tween(d, easing = FastOutSlowInEasing))
                    levels[i].animateTo(0.25f, tween(d, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    Canvas(modifier.size(16.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * 2) / 3
        levels.forEachIndexed { i, a ->
            val h = size.height * a.value
            drawRoundRect(
                color,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** Tombol play bulat bergradasi khas Freease. */
@Composable
fun PlayFab(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    loading: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .shadow(14.dp, CircleShape, ambientColor = Freease.accent.primary, spotColor = Freease.accent.primary)
            .clip(CircleShape)
            .background(Freease.accent.brush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(size * 0.5f), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Jeda" else "Putar",
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Freease.colors.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Freease.accent.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PillButton(action, onAction)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    EmptyState(Icons.Rounded.CloudOff, "Gagal memuat", message, modifier, "Coba lagi", onRetry)

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(Color.White)
                else Modifier.background(Color.White.copy(alpha = 0.08f)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) Color.Black else Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) Color.Black else Color.White)
    }
}

@Composable
fun FilterChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { i ->
            val sel = i == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .then(
                        if (sel) Modifier.background(Freease.accent.brush)
                        else Modifier.background(Freease.colors.card),
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    options[i],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Kotak placeholder berkilau saat konten dimuat. */
@Composable
fun Shimmer(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.clip(shape).background(Freease.colors.card))
}

@Composable
fun SongListPlaceholder(count: Int = 8) {
    Column(Modifier.fillMaxWidth()) {
        repeat(count) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Shimmer(Modifier.width(180.dp).height(14.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(8.dp))
                    Shimmer(Modifier.width(110.dp).height(11.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
fun SmallLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), color = Freease.accent.primary, strokeWidth = 2.5.dp)
    }
}
