package com.freease.music.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.freease.music.util.Format

/** Seek bar tipis; jempol membesar saat digeser, waktu ikut berubah selama drag. */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    live: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction
    else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(if (dragging) 16.dp else 12.dp, label = "thumb")
    val track by animateDpAsState(if (dragging) 6.dp else 4.dp, label = "track")

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0 || live) return@pointerInput
                    detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0 || live) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { onSeek((dragFraction * durationMs).toLong()); dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val h = track.toPx()
            val y = size.height / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(color.copy(alpha = 0.22f), Offset(0f, y - h / 2), Size(size.width, h), r)
            drawRoundRect(color.copy(alpha = 0.35f), Offset(0f, y - h / 2), Size(size.width * bufferFraction, h), r)
            drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * fraction, h), r)
            if (!live) drawCircle(color, thumb.toPx() / 2, Offset(size.width * fraction, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (live) "● LIVE" else Format.durationMs(if (dragging) (dragFraction * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) Color(0xFFFF4D6D) else color.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            if (!live) {
                Text(Format.durationMs(durationMs), style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
            }
        }
    }
}
