package com.freease.music.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.db.DownloadState
import com.freease.music.data.db.HistoryRow
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.CollectionScreen
import com.freease.music.ui.components.ConfirmDialog
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.FilterChipRow
import com.freease.music.ui.components.HeaderIcon
import com.freease.music.ui.components.LikedCover
import com.freease.music.ui.components.LocalPlaylistRow
import com.freease.music.ui.components.MosaicCover
import com.freease.music.ui.components.SongRow
import com.freease.music.ui.components.TextInputDialog
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.Routes
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LibraryScreen() {
    val actions = LocalActions.current
    val playlists by remember { actions.library.playlistSummaries() }.collectAsState(emptyList())
    val artists by remember { actions.library.followedArtists() }.collectAsState(emptyList())
    val likedCount by remember { actions.library.likedCount() }.collectAsState(0)
    val downloads by remember { actions.library.downloads() }.collectAsState(emptyList())
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sortAz by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Freease.accent.brush),
                contentAlignment = Alignment.Center,
            ) { Text("F", fontWeight = FontWeight.Black, color = Color.White) }
            Spacer(Modifier.width(12.dp))
            Text("Koleksi Kamu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { sortAz = !sortAz }) { Icon(Icons.Rounded.Sort, "Urutkan") }
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, "Tambah") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = Freease.colors.elevated) {
                    DropdownMenuItem(
                        text = { Text("Playlist baru") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { addMenu = false; creating = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Impor dari Spotify") },
                        leadingIcon = { Icon(Icons.Rounded.Download, null) },
                        onClick = { addMenu = false; actions.navigate(Routes.import()) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FilterChipRow(listOf("Semua", "Playlist", "Artis", "Unduhan"), filter, { filter = it })
        Spacer(Modifier.height(8.dp))
        Text(
            if (sortAz) "Urut: A–Z" else "Urut: Terbaru",
            style = MaterialTheme.typography.labelMedium,
            color = Freease.colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val sortedPlaylists = if (sortAz) playlists.sortedBy { it.name.lowercase() } else playlists
        val sortedArtists = if (sortAz) artists.sortedBy { it.name.lowercase() } else artists
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
            if (filter == 0 || filter == 1) {
                item {
                    FixedRow("Lagu yang Disukai", "Playlist • $likedCount lagu", onClick = { actions.navigate(Routes.LIKED) }) {
                        LikedCover(Modifier.fillMaxSize())
                    }
                }
            }
            if (filter == 0 || filter == 3) {
                item {
                    val done = downloads.count { it.downloadState == DownloadState.DONE }
                    FixedRow("Unduhan", "Offline • $done lagu", onClick = { actions.navigate(Routes.DOWNLOADS) }) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0FB57D), Color(0xFF0D5E91)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.DownloadDone, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
                item {
                    FixedRow("Musik di HP", "MP3, FLAC, M4A yang tersimpan di HP", onClick = { actions.navigate(Routes.LOCAL_MUSIC) }) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFFF8A3D), Color(0xFFB4235A)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
                item {
                    FixedRow("Alarm Musik", "Bangun dengan lagu favoritmu", onClick = { actions.navigate(Routes.ALARMS) }) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFFFC94D), Color(0xFFE0582B)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Alarm, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
            if (filter == 0) {
                item {
                    FixedRow("Monthly Freease", "Rekap, pencapaian, dan favorit bulananmu", onClick = { actions.navigate(Routes.MONTHLY) }) {
                        Box(Modifier.fillMaxSize().background(Freease.accent.brush), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                }
                item {
                    FixedRow("Freease Wrapped", "Rekap setahun: artis, lagu, dan tipe pendengarmu", onClick = {
                        actions.navigate(Routes.wrapped(com.freease.music.ui.screens.defaultWrappedYear()))
                    }) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF7B4DFF), Color(0xFFE8115B), Color(0xFFFF6437)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
                item {
                    FixedRow("Riwayat putar", "Semua yang pernah kamu dengar", onClick = { actions.navigate(Routes.HISTORY) }) {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A3A55), Color(0xFF1E1E2C)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.History, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
            if (filter == 0 || filter == 1) {
                items(sortedPlaylists, key = { "p${it.id}" }) { p -> LocalPlaylistRow(p) { actions.openLocal(p.id) } }
            }
            if (filter == 0 || filter == 2) {
                items(sortedArtists, key = { "a${it.url}" }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.openArtist(a.url) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(a.thumbnail, Modifier.size(60.dp), shape = CircleShape)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Artis", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                        }
                    }
                }
            }
            if (filter == 3) {
                items(downloads.filter { it.downloadState != DownloadState.DONE }, key = { "d${it.id}" }) { e ->
                    DownloadingRow(e.toSong().title, e.downloadState, e.downloadProgress)
                }
            }
            if ((filter == 1 && playlists.isEmpty()) || (filter == 2 && artists.isEmpty())) {
                item {
                    EmptyState(
                        Icons.Rounded.LibraryMusic,
                        if (filter == 1) "Belum ada playlist" else "Belum mengikuti artis",
                        if (filter == 1) "Buat playlist pertamamu dan simpan lagu favorit." else "Buka halaman artis lalu ketuk Ikuti.",
                        action = if (filter == 1) "Buat playlist" else null,
                        onAction = if (filter == 1) ({ creating = true }) else null,
                    )
                }
            }
        }
    }
    if (creating) {
        TextInputDialog("Beri nama playlist", "", "Buat", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch { actions.openLocal(actions.library.createPlaylist(name)) }
        }
    }
}

@Composable
private fun FixedRow(title: String, subtitle: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp))) { cover() }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
        }
    }
}

@Composable
private fun DownloadingRow(title: String, state: Int, progress: Float) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when (state) {
                DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                DownloadState.RUNNING -> CircularProgressIndicator(progress = { progress }, Modifier.size(28.dp), color = Freease.accent.primary, strokeWidth = 3.dp)
                else -> CircularProgressIndicator(Modifier.size(28.dp), color = Freease.colors.muted, strokeWidth = 3.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (state) {
                    DownloadState.FAILED -> "Gagal — ketuk menu lagu untuk mencoba lagi"
                    DownloadState.RUNNING -> "Mengunduh ${(progress * 100).toInt()}%"
                    else -> "Menunggu…"
                },
                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LikedScreen() {
    val actions = LocalActions.current
    val songs by remember { actions.library.liked() }.collectAsState(emptyList())
    val total = songs.sumOf { it.durationSec }
    CollectionScreen(
        title = "Lagu yang Disukai",
        subtitle = "${songs.size} lagu • ${Format.longDuration(total)}",
        from = "Lagu yang Disukai",
        songs = songs,
        colorUrl = null,
        cover = { m -> Box(m.clip(RoundedCornerShape(10.dp))) { LikedCover(Modifier.fillMaxSize()) } },
        headerActions = {
            HeaderIcon(Icons.Rounded.Download, "Unduh semua") {
                actions.container.downloads.download(songs)
                actions.toast("Mengunduh ${songs.size} lagu")
            }
        },
        empty = {
            EmptyState(Icons.Rounded.FavoriteBorder, "Lagu yang kamu sukai ada di sini", "Ketuk ikon hati pada lagu untuk menyimpannya.")
        },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadsScreen() {
    val actions = LocalActions.current
    val all by remember { actions.library.downloads() }.collectAsState(emptyList())
    val done = all.filter { it.downloadState == DownloadState.DONE }
    val pending = all.filter { it.downloadState != DownloadState.DONE }
    val bytes = done.sumOf { it.downloadBytes }
    CollectionScreen(
        title = "Unduhan",
        subtitle = "${done.size} lagu • ${Format.bytes(bytes)} • bisa diputar tanpa internet",
        from = "Unduhan",
        songs = done.map { it.toSong() },
        colorUrl = null,
        cover = { m ->
            Box(
                m.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF0FB57D), Color(0xFF0D5E91)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DownloadDone, null, tint = Color.White, modifier = Modifier.size(84.dp)) }
        },
        empty = {
            if (pending.isEmpty()) {
                EmptyState(Icons.Rounded.Download, "Belum ada unduhan", "Unduh lagu dari menu ⋮ supaya bisa didengar tanpa kuota.")
            }
        },
        footer = {
            if (pending.isNotEmpty()) {
                item { Text("Dalam antrean", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
                items(pending, key = { "pend_${it.id}" }) { e -> DownloadingRow(e.title, e.downloadState, e.downloadProgress) }
            }
        },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LocalPlaylistScreen(id: Long) {
    val actions = LocalActions.current
    val playlist by remember(id) { actions.library.playlist(id) }.collectAsState(null)
    val songs by remember(id) { actions.library.playlistSongs(id) }.collectAsState(emptyList())
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val p = playlist ?: return
    val total = songs.sumOf { it.durationSec }

    CollectionScreen(
        title = p.name,
        subtitle = listOfNotNull(
            p.description.takeIf { it.isNotBlank() },
            "${songs.size} lagu • ${Format.longDuration(total)}",
        ).joinToString("\n"),
        from = p.name,
        songs = songs,
        colorUrl = songs.firstOrNull()?.artworkSmall,
        playlistId = id,
        cover = { m -> MosaicCover(songs.take(4).map { it.id }, m, RoundedCornerShape(10.dp)) },
        headerActions = {
            HeaderIcon(Icons.Rounded.Download, "Unduh semua") {
                actions.container.downloads.download(songs)
                actions.toast("Mengunduh ${songs.size} lagu")
            }
            HeaderIcon(Icons.Rounded.Edit, "Ubah") { editing = true }
            Box {
                HeaderIcon(Icons.Rounded.Sort, "Lainnya") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Freease.colors.elevated) {
                    DropdownMenuItem(
                        text = { Text(if (p.pinned) "Lepas sematan" else "Sematkan di atas") },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                        onClick = { menu = false; scope.launch { actions.library.setPinned(id, !p.pinned) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Tambah ke antrean") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                        onClick = { menu = false; actions.player.addToQueue(songs) },
                    )
                    DropdownMenuItem(
                        text = { Text("Bagikan daftar lagu") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            menu = false
                            actions.shareText(
                                "🎧 ${p.name}\n\n" + songs.mapIndexed { i, s -> "${i + 1}. ${s.title} — ${s.artist}" }.joinToString("\n"),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus playlist", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; deleting = true },
                    )
                }
            }
        },
        empty = {
            EmptyState(
                Icons.AutoMirrored.Rounded.PlaylistAdd,
                "Playlist ini masih kosong",
                "Cari lagu lalu pilih ⋮ → Tambahkan ke playlist.",
                action = "Cari lagu",
                onAction = { actions.navigate(Routes.SEARCH) },
            )
        },
    )
    if (editing) {
        TextInputDialog(
            title = "Ubah playlist", initial = p.name, confirm = "Simpan", onDismiss = { editing = false },
            secondLabel = "Deskripsi", secondInitial = p.description,
            onConfirm2 = { name, desc ->
                editing = false
                scope.launch { actions.library.renamePlaylist(id, name, desc) }
            },
        )
    }
    if (deleting) {
        ConfirmDialog("Hapus playlist?", "\"${p.name}\" akan dihapus permanen.", "Hapus", { deleting = false }) {
            scope.launch {
                actions.library.deletePlaylist(id)
                actions.nav.popBackStack()
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun HistoryScreen() {
    val actions = LocalActions.current
    val rows by remember { actions.library.historyRows() }.collectAsState(emptyList())
    var confirm by remember { mutableStateOf(false) }
    val grouped = remember(rows) { rows.groupBy { dayLabel(it.playedAt) } }
    val songs = remember(rows) { rows.map { it.song.toSong() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
            Text("Riwayat putar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (rows.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Hapus riwayat") }
        }
        if (rows.isEmpty()) {
            EmptyState(Icons.Rounded.History, "Belum ada riwayat", "Lagu yang kamu dengarkan akan muncul di sini.")
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
            var offset = 0
            grouped.forEach { (label, list) ->
                item(key = "h_$label") {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                val start = offset
                items(list.size, key = { "r_${list[it].playedAt}_${list[it].song.id}" }) { i ->
                    val row: HistoryRow = list[i]
                    SongRow(
                        row.song.toSong(),
                        onClick = { actions.player.play(songs, start + i, from = "Riwayat") },
                        subtitle = "${row.song.artist} • ${SimpleDateFormat("HH:mm", Locale("id")).format(row.playedAt)}",
                    )
                }
                offset += list.size
            }
        }
    }
    if (confirm) {
        ConfirmDialog("Hapus riwayat?", "Riwayat & statistik dengar akan dikosongkan.", "Hapus", { confirm = false }) {
            actions.container.appScope.launch { actions.library.clearHistory() }
        }
    }
}

private fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    c.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    c.timeInMillis = ts
    val d = c.get(Calendar.DAY_OF_YEAR) to c.get(Calendar.YEAR)
    return when (d) {
        today -> "Hari ini"
        yesterday -> "Kemarin"
        else -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id")).format(ts)
    }
}
