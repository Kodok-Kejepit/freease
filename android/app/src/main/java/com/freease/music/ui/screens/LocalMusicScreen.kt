package com.freease.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Song
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.FilterChipRow
import com.freease.music.ui.components.PlayFab
import com.freease.music.ui.components.SongListPlaceholder
import com.freease.music.ui.components.SongRow
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/** Lagu yang tersimpan di HP (MP3, FLAC, M4A, dll.) lewat MediaStore. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LocalMusicScreen() {
    val actions = LocalActions.current
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(granted) {
        if (granted) songs = runCatching { actions.container.localMusic.songs() }.getOrDefault(emptyList())
    }
    val shown = remember(songs, query, sort) {
        val q = query.trim().lowercase()
        val base = songs.orEmpty().filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album?.lowercase()?.contains(q) == true }
        when (sort) {
            1 -> base.sortedBy { it.title.lowercase() }
            2 -> base.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.orEmpty() }, { it.title.lowercase() }))
            else -> base
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
            Column(Modifier.weight(1f)) {
                Text("Musik di HP", style = MaterialTheme.typography.titleLarge)
                songs?.let { list ->
                    Text(
                        "${list.size} lagu • ${Format.longDuration(list.sumOf { it.durationSec })}",
                        style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                    )
                }
            }
            if (shown.isNotEmpty()) {
                IconButton(onClick = { actions.player.play(shown, shuffle = true, from = "Musik di HP") }) { Icon(Icons.Rounded.Shuffle, "Acak") }
                PlayFab(false, { actions.player.play(shown, from = "Musik di HP") }, Modifier.padding(end = 12.dp), size = 44.dp)
            }
        }
        when {
            !granted -> EmptyState(
                Icons.Rounded.FolderOpen,
                "Izinkan akses musik",
                "Freease perlu izin membaca berkas audio untuk memutar lagu yang tersimpan di HP-mu.",
                action = "Izinkan",
                onAction = { launcher.launch(audioPermission) },
            )
            songs == null -> SongListPlaceholder()
            songs!!.isEmpty() -> EmptyState(Icons.Rounded.FolderOpen, "Belum ada lagu di HP", "Lagu MP3/FLAC/M4A di folder Musik atau Download akan muncul di sini.")
            else -> {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(44.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Freease.colors.card).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Freease.colors.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Cari judul, artis, album", color = Freease.colors.muted)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Freease.accent.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                FilterChipRow(listOf("Terbaru", "Judul", "Artis"), sort, { sort = it })
                LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = LocalBottomInset.current + 16.dp)) {
                    itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            s,
                            onClick = { actions.player.play(shown, i, from = "Musik di HP") },
                            subtitle = listOfNotNull(s.artist, s.album, Format.duration(s.durationSec)).joinToString(" • "),
                        )
                    }
                }
            }
        }
    }
}
