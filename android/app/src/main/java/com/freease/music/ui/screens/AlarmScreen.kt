package com.freease.music.ui.screens

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freease.music.data.alarm.Alarm
import com.freease.music.playback.LibraryTree
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease

/** Daftar alarm musik: bangun dengan lagu favorit, volume naik perlahan. */
@Composable
fun AlarmScreen() {
    val actions = LocalActions.current
    val context = LocalContext.current
    val store = actions.container.alarms
    val alarms by store.alarms.collectAsState()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    val exactAllowed = remember {
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text("Alarm Musik", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            if (!exactAllowed) {
                Row(
                    Modifier.padding(16.dp, 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33FFB020))
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")),
                                )
                            }
                        }.padding(12.dp),
                ) {
                    Text("Izin \"Alarm & pengingat\" belum aktif — alarm bisa telat beberapa menit. Ketuk untuk mengaktifkan.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (alarms.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Alarm, "Belum ada alarm",
                    "Bangun dengan lagu yang kamu suka. Volume naik pelan-pelan, dan kalau internet mati Freease memutar unduhan atau nada alarm bawaan.",
                    action = "Buat alarm", onAction = { editing = newAlarm(store.newId()) },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = LocalBottomInset.current + 96.dp)) {
                    items(alarms, key = { it.id }) { a ->
                        AlarmRow(a, onClick = { editing = a }, onToggle = { store.upsert(a.copy(enabled = it)) })
                    }
                }
            }
        }
        if (alarms.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = LocalBottomInset.current + 20.dp)
                    .size(56.dp).clip(CircleShape).background(Freease.accent.primary).clickable { editing = newAlarm(store.newId()) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "Tambah alarm", tint = Color.Black) }
        }
    }

    editing?.let { a ->
        AlarmEditor(
            initial = a,
            isNew = store.get(a.id) == null,
            onDismiss = { editing = null },
            onDelete = { store.delete(a.id); editing = null },
            onSave = { saved ->
                store.upsert(saved.copy(enabled = true))
                editing = null
                actions.container.emitMessage("Alarm berbunyi ${untilText(saved)}")
            },
        )
    }
}

private fun newAlarm(id: Int) = Alarm(id, 6, 0)

/** "dalam 7 jam 15 menit" dsb. */
private fun untilText(a: Alarm): String {
    val now = System.currentTimeMillis()
    val mins = ((Alarm.nextTrigger(a, now) - now) / 60_000).toInt() + 1
    val d = mins / (60 * 24)
    val h = (mins / 60) % 24
    val m = mins % 60
    return buildString {
        append("dalam ")
        if (d > 0) append("$d hari ")
        if (h > 0) append("$h jam ")
        if (d == 0 && (m > 0 || h == 0)) append("$m menit")
    }.trim()
}

@Composable
private fun AlarmRow(a: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val alpha = if (a.enabled) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Freease.colors.card).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.timeText, fontSize = 40.sp, fontWeight = FontWeight.Light, color = Color.White.copy(alpha = alpha))
            Text(
                "${a.daysText} • ${a.sourceLabel}",
                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted.copy(alpha = alpha),
            )
            if (a.enabled) {
                Text(untilText(a), style = MaterialTheme.typography.labelSmall, color = Freease.accent.primary)
            }
        }
        Switch(
            a.enabled, onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary, checkedThumbColor = Color.White),
        )
    }
}

private data class SourceOption(val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(initial: Alarm, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Alarm) -> Unit) {
    val actions = LocalActions.current
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var days by remember { mutableStateOf(initial.days) }
    var source by remember { mutableStateOf(SourceOption(initial.source, initial.sourceLabel)) }
    var fade by remember { mutableIntStateOf(initial.fadeSec) }
    var shuffle by remember { mutableStateOf(initial.shuffle) }
    var sourceMenu by remember { mutableStateOf(false) }
    val playlists by remember { actions.library.playlistSummaries() }.collectAsState(emptyList())
    val sources = remember(playlists) {
        listOf(
            SourceOption(LibraryTree.LIKED, "Lagu yang Disukai"),
            SourceOption(LibraryTree.RECENT, "Baru diputar"),
            SourceOption(LibraryTree.DOWNLOADS, "Unduhan"),
            SourceOption(LibraryTree.LOCAL, "Musik di HP"),
        ) + playlists.map { SourceOption("${LibraryTree.PLAYLIST_PREFIX}${it.id}", it.name) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text(if (isNew) "Alarm baru" else "Ubah alarm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(
                    time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Freease.colors.card,
                        selectorColor = Freease.accent.primary,
                        timeSelectorSelectedContainerColor = Freease.accent.primary.copy(alpha = 0.25f),
                        timeSelectorUnselectedContainerColor = Freease.colors.card,
                    ),
                )
                Text("Ulangi", style = MaterialTheme.typography.labelLarge, color = Freease.colors.muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Alarm.DAY_ORDER.forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) Freease.accent.primary else Freease.colors.card)
                                .clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(Alarm.DAY_SHORT.getValue(d).take(1), color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    Alarm(0, 0, 0, days).daysText,
                    style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(14.dp))
                Text("Lagu", style = MaterialTheme.typography.labelLarge, color = Freease.colors.muted)
                Box {
                    Row(
                        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Freease.colors.card)
                            .clickable { sourceMenu = true }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(source.label, Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.KeyboardArrowDown, null)
                    }
                    DropdownMenu(sourceMenu, { sourceMenu = false }) {
                        sources.forEach { o ->
                            DropdownMenuItem(text = { Text(o.label) }, onClick = { source = o; sourceMenu = false })
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Volume naik perlahan", style = MaterialTheme.typography.labelLarge, color = Freease.colors.muted)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5 to "Cepat", 30 to "30 dtk", 60 to "1 mnt", 180 to "3 mnt").forEach { (sec, label) ->
                        val on = fade == sec
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) Freease.accent.primary else Freease.colors.card)
                                .clickable { fade = sec }.padding(horizontal = 12.dp, vertical = 7.dp),
                        ) { Text(label, color = if (on) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }

                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Acak urutan lagu", Modifier.weight(1f))
                    Switch(shuffle, { shuffle = it }, colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary))
                }
                if (!isNew) {
                    Row(
                        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFF6B6B))
                        Spacer(Modifier.width(6.dp))
                        Text("Hapus alarm", color = Color(0xFFFF6B6B))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(hour = time.hour, minute = time.minute, days = days, source = source.id, sourceLabel = source.label, fadeSec = fade, shuffle = shuffle))
            }) { Text("Simpan", color = Freease.accent.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Color.White) } },
    )
}
