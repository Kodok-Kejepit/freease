package com.freease.music.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.importer.SpotifyReader
import com.freease.music.ui.components.PillButton
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease

private val SpotifyGreen = Color(0xFF1ED760)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ImportScreen(initialLink: String) {
    val actions = LocalActions.current
    val importer = actions.container.importer
    val state by importer.state.collectAsState()
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf(initialLink) }
    val valid = SpotifyReader.isSpotifyLink(link)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
            Text("Impor dari Spotify", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(SpotifyGreen.copy(alpha = 0.35f), Freease.colors.card)))
                            .padding(16.dp),
                    ) {
                        Text(
                            "Di Spotify: buka playlist/album → ⋯ → Bagikan → Salin link. Tempel di sini, Freease " +
                                "akan mencari setiap lagunya di YouTube dan membuat playlist yang sama.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.running,
                        placeholder = { Text("https://open.spotify.com/playlist/…") },
                        trailingIcon = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it.trim() }
                            }) { Icon(Icons.Rounded.ContentPaste, "Tempel") }
                        },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SpotifyGreen, cursorColor = SpotifyGreen),
                    )
                    if (link.isNotBlank() && !valid) {
                        Text("Ini bukan link playlist/album/lagu Spotify.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            state.running -> PillButton("Batalkan", { importer.cancel() }, filled = false)
                            state.finished -> {
                                PillButton("Buka playlist", { state.playlistId?.let { actions.openLocal(it) } })
                                PillButton("Impor lagi", { importer.reset(); link = "" }, filled = false)
                            }
                            else -> PillButton("Impor", { if (valid) importer.start(link.trim()) })
                        }
                    }
                }
            }
            if (state.error != null) {
                item {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            if (state.items.isNotEmpty() || state.running) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(state.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(6.dp))
                        val total = state.items.size
                        Text(
                            when {
                                total == 0 -> "Membaca playlist…"
                                state.running -> "Mencari di YouTube… ${state.processed}/$total"
                                else -> "Selesai: ${state.found} dari $total lagu ditemukan"
                            },
                            color = Freease.colors.muted,
                        )
                        if (state.truncated) {
                            Text(
                                "Spotify hanya membagikan 100 lagu pertama dari playlist ini.",
                                color = Freease.colors.muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else state.processed.toFloat() / total },
                            modifier = Modifier.fillMaxWidth().clip(CircleShape),
                            color = SpotifyGreen,
                        )
                    }
                }
            }
            itemsIndexed(state.items, key = { i, _ -> i }) { _, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        when {
                            !item.done -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Freease.colors.muted)
                            item.song != null -> Icon(Icons.Rounded.CheckCircle, null, tint = SpotifyGreen)
                            else -> Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Freease.colors.muted)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !item.done -> item.track.artists
                                item.song != null -> "${item.track.artists}  →  ${item.song.title} · ${item.song.artist}"
                                else -> "${item.track.artists} · tidak ditemukan"
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                        )
                    }
                }
            }
        }
    }
}
