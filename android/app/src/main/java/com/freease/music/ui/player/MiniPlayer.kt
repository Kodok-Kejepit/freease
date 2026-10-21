package com.freease.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.darken
import com.freease.music.ui.components.rememberArtworkColor
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val song = state.song ?: return
    val liked by remember(song.id) { actions.library.isLiked(song.id) }.collectAsState(false)
    val color by rememberArtworkColor(song.artworkSmall, Freease.colors.card)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.darken(0.25f))
            .clickable { actions.playerExpanded = true }
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = offset.value
                        scope.launch {
                            when {
                                v < -140 -> actions.player.next()
                                v > 140 -> actions.player.previous()
                            }
                            offset.animateTo(0f)
                        }
                    },
                ) { _, drag -> scope.launch { offset.snapTo(offset.value + drag) } }
            },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = 8.dp, end = 4.dp)
                .graphicsLayer { translationX = offset.value; alpha = 1f - (kotlin.math.abs(offset.value) / 600f).coerceIn(0f, 0.6f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.artworkSmall, Modifier.size(44.dp), fallback = Thumbs.fallback(song.id), shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { actions.toggleLike(song) }) {
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Suka",
                    tint = if (liked) Freease.accent.secondary else Color.White,
                )
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (state.isBuffering && state.playWhenReady) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { actions.player.toggle() }) {
                        Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Putar/Jeda", modifier = Modifier.size(30.dp))
                    }
                }
            }
            IconButton(onClick = { actions.player.next() }, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, "Berikutnya")
            }
        }
        MiniProgress(state.durationMs, Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MiniProgress(durationMs: Long, modifier: Modifier) {
    val actions = LocalActions.current
    val position by remember { actions.player.positionFlow(1000) }.collectAsStateWithLifecycle(0L)
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val f = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        drawRect(Color.White.copy(alpha = 0.2f))
        drawRect(Color.White, size = size.copy(width = size.width * f))
    }
}
