package com.freease.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.SongMenuRequest
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(request: SongMenuRequest, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val song = request.song
    val entity by remember(song.id) { actions.library.observeSong(song.id) }.collectAsState(null)
    val liked = entity?.liked == true
    val dlState = entity?.downloadState ?: DownloadState.NONE
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun run(block: () -> Unit) {
        block()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Freease.colors.elevated,
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.artworkSmall, Modifier.size(56.dp), fallback = Thumbs.fallback(song.id), shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted, maxLines = 1)
                }
            }
            HorizontalDivider(color = Freease.colors.divider)
            Spacer(Modifier.height(4.dp))
            SheetAction(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Hapus dari Lagu yang Disukai" else "Suka",
                tint = if (liked) Freease.accent.secondary else Color.White,
            ) { run { actions.toggleLike(song) } }
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Tambahkan ke playlist") {
                run { actions.addToPlaylist = listOf(song) }
            }
            if (request.queueIndex == null) {
                SheetAction(Icons.Rounded.SkipNext, "Putar berikutnya") {
                    run { actions.player.playNext(listOf(song)); actions.toast("Diputar setelah lagu ini") }
                }
                SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Tambahkan ke antrean") {
                    run { actions.player.addToQueue(listOf(song)); actions.toast("Ditambahkan ke antrean") }
                }
            } else {
                SheetAction(Icons.Rounded.VerticalAlignTop, "Pindah ke berikutnya") {
                    run {
                        val target = actions.player.state.value.index + 1
                        if (request.queueIndex != target) {
                            actions.player.move(request.queueIndex, if (request.queueIndex < target) target - 1 else target)
                        }
                    }
                }
                SheetAction(Icons.Rounded.PlaylistRemove, "Hapus dari antrean") {
                    run { actions.player.remove(request.queueIndex) }
                }
            }
            if (!song.isLive && !song.isLocal) {
                when (dlState) {
                    DownloadState.DONE -> SheetAction(Icons.Rounded.DownloadDone, "Hapus unduhan", tint = Freease.accent.primary) {
                        run { actions.container.downloads.remove(song.id) }
                    }
                    DownloadState.QUEUED, DownloadState.RUNNING -> SheetAction(Icons.Rounded.Download, "Batalkan unduhan") {
                        run { actions.container.downloads.remove(song.id) }
                    }
                    else -> SheetAction(Icons.Rounded.Download, "Unduh (dengar offline)") {
                        run { actions.container.downloads.download(listOf(song)); actions.toast("Mengunduh \"${song.title}\"") }
                    }
                }
            }
            if (!song.isLocal) SheetAction(Icons.Rounded.Radio, "Mulai radio lagu") {
                run { actions.player.startRadio(song) }
            }
            if (song.artistUrl != null) {
                SheetAction(Icons.Rounded.Person, "Lihat artis") { run { actions.openArtist(song.artistUrl) } }
            }
            if (request.playlistId != null) {
                SheetAction(Icons.Rounded.Delete, "Hapus dari playlist ini") {
                    run { scope.launch { actions.library.removeFromPlaylist(request.playlistId, song.id) } }
                }
            }
            if (!song.isLocal) SheetAction(Icons.Rounded.Share, "Bagikan") { run { actions.share(song) } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val playlists by remember { actions.library.playlistSummaries() }.collectAsState(emptyList())
    val containing by remember(songs) {
        if (songs.size == 1) actions.library.playlistsContaining(songs[0].id) else flowOf(emptyList())
    }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Freease.colors.elevated) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Tambahkan ke playlist",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(56.dp).background(Freease.accent.brush, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, tint = Color.White) }
                Spacer(Modifier.width(14.dp))
                Text("Playlist baru", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(playlists, key = { it.id }) { p ->
                    val has = p.id in containing
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                if (has && songs.size == 1) {
                                    actions.library.removeFromPlaylist(p.id, songs[0].id)
                                    actions.toast("Dihapus dari ${p.name}")
                                } else {
                                    actions.library.addToPlaylist(p.id, songs)
                                    actions.toast("Ditambahkan ke ${p.name}")
                                }
                                onDismiss()
                            }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MosaicCover(p.covers, Modifier.size(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${p.songCount} lagu", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                        }
                        if (has) Icon(Icons.Rounded.CheckCircle, null, tint = Freease.accent.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (creating) {
        TextInputDialog(
            title = "Beri nama playlist",
            initial = "",
            confirm = "Buat",
            onDismiss = { creating = false },
        ) { name ->
            creating = false
            scope.launch {
                val id = actions.library.createPlaylist(name)
                actions.library.addToPlaylist(id, songs)
                actions.toast("Playlist \"$name\" dibuat")
                onDismiss()
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    secondLabel: String? = null,
    secondInitial: String = "",
    onConfirm2: ((String, String) -> Unit)? = null,
    onConfirm: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    var text2 by remember { mutableStateOf(secondInitial) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Freease.accent.primary,
        cursorColor = Freease.accent.primary,
        focusedLabelColor = Freease.accent.primary,
    )
    fun submit() {
        if (text.isBlank()) return
        if (onConfirm2 != null) onConfirm2(text.trim(), text2.trim()) else onConfirm(text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Nama") },
                    colors = colors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (secondLabel != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text2,
                        onValueChange = { text2 = it },
                        label = { Text(secondLabel) },
                        colors = colors,
                        maxLines = 3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(confirm, color = Freease.accent.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Freease.colors.muted) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text(title) },
        text = { Text(text, color = Freease.colors.muted) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = Freease.colors.muted) } },
    )
}

/** Tampilkan laporan crash/ANR dengan opsi bagikan & salin. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CrashReportDialog(report: String, title: String, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Kirim laporan ini ke pengembang supaya penyebabnya bisa diperbaiki.",
                    color = Freease.colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.shareText(report); onDismiss() }) {
                Text("Bagikan", color = Freease.accent.primary)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                    actions.toast("Laporan disalin")
                }) { Text("Salin", color = Freease.colors.muted) }
                TextButton(onClick = onDismiss) { Text("Tutup", color = Freease.colors.muted) }
            }
        },
    )
}
