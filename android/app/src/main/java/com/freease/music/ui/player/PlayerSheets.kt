package com.freease.music.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.components.Artwork
import com.freease.music.playback.smart.SmartMood
import com.freease.music.ui.components.PlayingBars
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.delay
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val queue by actions.player.queue.collectAsState()
    val state by actions.player.state.collectAsState()
    val settings by actions.container.settings.flow.collectAsState()
    val from by actions.player.playingFrom.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Freease.colors.elevated) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Antrean", style = MaterialTheme.typography.titleLarge)
                    if (from.isNotBlank()) Text("Diputar dari $from", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                }
                TextButton(onClick = { actions.player.clearUpcoming() }) { Text("Bersihkan", color = Freease.colors.muted) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Freease.colors.card).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Freease Pintar", style = MaterialTheme.typography.titleSmall)
                    Text("Sisipkan lagu pilihan yang cocok dengan seleramu", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                }
                Switch(
                    checked = settings.smartMode,
                    onCheckedChange = { actions.container.settings.setSmartMode(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Freease.colors.card).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Putar otomatis", style = MaterialTheme.typography.titleSmall)
                    Text("Lanjutkan dengan lagu serupa saat antrean habis", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                }
                Switch(
                    checked = settings.autoplay,
                    onCheckedChange = { actions.container.settings.setAutoplay(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary),
                )
            }
            LazyColumn(state = listState) {
                items(queue, key = { "${it.index}_${it.song.id}" }) { entry ->
                    val current = entry.index == state.index
                    val past = entry.index < state.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { actions.player.skipTo(entry.index) },
                                onLongClick = { actions.showMenu(entry.song, queueIndex = entry.index) },
                            )
                            .background(if (current) Freease.accent.primary.copy(alpha = 0.14f) else Color.Transparent)
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Artwork(entry.song.artworkSmall, Modifier.size(48.dp), fallback = Thumbs.fallback(entry.song.id), shape = RoundedCornerShape(6.dp))
                            if (current) {
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                    PlayingBars(playing = state.isPlaying, color = Color.White)
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                color = when {
                                    current -> Freease.accent.primary
                                    past -> Color.White.copy(alpha = 0.45f)
                                    else -> Color.White
                                },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (entry.song.isSmartPick) {
                                    Icon(Icons.Rounded.AutoAwesome, "Pilihan Freease Pintar", tint = Freease.accent.primary, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    entry.song.artist + if (entry.song.durationSec > 0) " • ${Format.duration(entry.song.durationSec)}" else "",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                                )
                            }
                        }
                        if (!current) {
                            IconButton(onClick = { if (entry.index > 0) actions.player.move(entry.index, entry.index - 1) }) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Naik", tint = Freease.colors.muted)
                            }
                            IconButton(onClick = { if (entry.index < queue.lastIndex) actions.player.move(entry.index, entry.index + 1) }) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Turun", tint = Freease.colors.muted)
                            }
                            IconButton(onClick = { actions.player.remove(entry.index) }) {
                                Icon(Icons.Rounded.Close, "Hapus", tint = Freease.colors.muted)
                            }
                        } else {
                            Icon(Icons.Rounded.DragHandle, null, tint = Color.Transparent, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val timer = actions.container.sleepTimer
    val st by timer.state.collectAsState()
    var now by remember { mutableFloatStateOf(System.currentTimeMillis().toFloat()) }
    LaunchedEffect(st) {
        while (st.endsAt != null) {
            now = System.currentTimeMillis().toFloat()
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text("Timer tidur") },
        text = {
            Column {
                if (st.active) {
                    val remaining = st.endsAt?.let { ((it - now.toLong()) / 1000).coerceAtLeast(0) }
                    Text(
                        when {
                            st.endOfTrack -> "Berhenti setelah lagu ini selesai"
                            st.songsLeft != null -> "Berhenti setelah ${st.songsLeft} lagu lagi"
                            else -> "Berhenti dalam ${Format.duration(remaining ?: 0)}"
                        },
                        color = Freease.accent.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (st.endsAt != null) {
                        Text(
                            "Tambah 10 menit",
                            style = MaterialTheme.typography.bodyLarge, color = Freease.accent.primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { timer.extend(10); actions.toast("Ditambah 10 menit") }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                    Text(
                        "$m menit",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { timer.start(m); actions.toast("Musik berhenti dalam $m menit"); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                listOf(2, 3, 5, 10).forEach { n ->
                    Text(
                        "Setelah $n lagu",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { timer.startSongs(n); actions.toast("Berhenti setelah $n lagu"); onDismiss() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                Text(
                    "Akhir lagu ini",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { timer.endOfTrack(); actions.toast("Berhenti setelah lagu ini"); onDismiss() }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
        },
        confirmButton = {
            if (st.active) TextButton(onClick = { timer.cancel(); onDismiss() }) { Text("Matikan timer", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Freease.colors.muted) } },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val store = actions.container.songParams
    val song = state.song
    var speed by remember { mutableFloatStateOf(state.speed) }
    var pitch by remember { mutableFloatStateOf(state.pitch) }
    var perSong by remember(song?.id) { mutableStateOf(song?.id?.let(store::get) != null) }
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Freease.accent.primary)

    fun apply(s: Float = speed, p: Float = pitch) {
        speed = s
        pitch = p
        actions.player.setPlaybackParameters(s, p)
        val params = com.freease.music.playback.SongParams.Params(s, p)
        val id = song?.id
        if (perSong && id != null) store.remember(id, params) else store.base = params
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text("Kecepatan & nada") },
        text = {
            Column {
                Text("Kecepatan ${String.format(Locale.US, "%.2fx", speed)}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = speed, onValueChange = { speed = (it * 20).toInt() / 20f },
                    valueRange = 0.5f..2f, colors = sliderColors,
                    onValueChangeFinished = { apply() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f).forEach { v ->
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (speed == v) Freease.accent.primary else Freease.colors.card)
                                .clickable { apply(s = v) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("${v}x", style = MaterialTheme.typography.labelMedium, color = if (speed == v) Color.Black else Color.White) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val semis = com.freease.music.playback.SongParams.semitones(pitch)
                Text(
                    "Nada ${String.format(Locale.US, "%.2f", pitch)}" + if (semis != 0) "  (kunci ${if (semis > 0) "+" else ""}$semis)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = pitch, onValueChange = { pitch = (it * 20).toInt() / 20f },
                    valueRange = 0.5f..2f, colors = sliderColors,
                    onValueChangeFinished = { apply() },
                )
                // Naik/turun satu kunci (semitone) — pas untuk menyesuaikan karaoke dengan suaramu.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Kunci", style = MaterialTheme.typography.labelLarge, color = Freease.colors.muted)
                    listOf(-1 to "−1", 1 to "+1").forEach { (d, label) ->
                        Box(
                            Modifier.clip(CircleShape).background(Freease.colors.card)
                                .clickable { apply(p = com.freease.music.playback.SongParams.pitchOf((semis + d).coerceIn(-12, 12))) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                if (song != null && !song.isLive) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ingat untuk lagu ini", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Otomatis dipakai setiap \"${song.title}\" diputar",
                                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 2,
                            )
                        }
                        Switch(
                            perSong,
                            { on ->
                                perSong = on
                                if (on) {
                                    store.remember(song.id, com.freease.music.playback.SongParams.Params(speed, pitch))
                                } else {
                                    store.forget(song.id)
                                    store.base.let { apply(it.speed, it.pitch) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(1f, 1f) }) {
                Text("Normal", color = Freease.accent.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Freease.colors.muted) } },
    )
}

private fun moodIcon(id: String?): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    "focus" -> Icons.Rounded.Lightbulb
    "chill" -> Icons.Rounded.Spa
    "workout" -> Icons.Rounded.FitnessCenter
    "sleep" -> Icons.Rounded.Bedtime
    else -> Icons.Rounded.AutoAwesome
}

/** Pilih suasana Freease Pintar; suasana Tidur sekaligus menyalakan timer tidur. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MoodDialog(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val settings by actions.container.settings.flow.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text("Suasana Freease Pintar") },
        text = {
            Column {
                val options = listOf<Pair<String, SmartMood?>>("" to null) + SmartMood.entries.map { it.id to it }
                options.forEach { (id, mood) ->
                    val selected = settings.smartMood == id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Freease.accent.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable {
                                actions.container.settings.setSmartMode(true)
                                actions.container.settings.setSmartMood(id)
                                if (mood == SmartMood.SLEEP) {
                                    actions.container.sleepTimer.start(45)
                                    actions.toast("Suasana Tidur: musik berhenti dalam 45 menit")
                                } else {
                                    actions.toast(if (mood == null) "Suasana: otomatis" else "Suasana ${mood.label} aktif")
                                }
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(moodIcon(id.ifEmpty { null }), null, tint = if (selected) Freease.accent.primary else Freease.colors.muted)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mood?.label ?: "Otomatis", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                mood?.blurb ?: "Mengikuti seleramu dan waktu hari ini",
                                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Freease.colors.muted) } },
    )
}
