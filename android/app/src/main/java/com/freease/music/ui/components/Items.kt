package com.freease.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.db.PlaylistSummary
import com.freease.music.data.model.ArtistItem
import com.freease.music.data.model.PlaylistItem
import com.freease.music.data.model.Song
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format

/** Baris lagu standar. Lagu yang sedang diputar ditandai warna aksen + animasi batang. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    subtitle: String? = null,
    playlistId: Long? = null,
    downloaded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val current = state.song?.id == song.id
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { actions.showMenu(song, playlistId) })
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (current) PlayingBars(playing = state.isPlaying)
                else Text("$index", style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted)
            }
        }
        Box {
            Artwork(song.artworkSmall, Modifier.size(52.dp), fallback = Thumbs.fallback(song.id), shape = RoundedCornerShape(8.dp))
            if (current && index == null) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(playing = state.isPlaying, color = Color.White) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (current) Freease.accent.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(Icons.Rounded.DownloadDone, null, tint = Freease.accent.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (song.isLive) {
                    Icon(Icons.Rounded.Sensors, null, tint = Color(0xFFFF4D6D), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle ?: buildString {
                        append(song.artist)
                        if (song.durationSec > 0) append(" • ").append(Format.duration(song.durationSec))
                        if (song.isLive) append(" • LIVE")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Freease.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
        IconButton(onClick = { actions.showMenu(song, playlistId) }) {
            Icon(Icons.Rounded.MoreVert, "Menu", tint = Freease.colors.muted)
        }
    }
}

/** Kartu persegi untuk baris horizontal (rekomendasi, trending). */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val actions = LocalActions.current
    Column(
        modifier
            .width(size)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = { actions.showMenu(song) }),
    ) {
        Artwork(song.artworkUrl, Modifier.size(size), fallback = Thumbs.fallback(song.id), shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistCard(item: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    Column(modifier.width(size).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box {
            Artwork(
                item.thumbnail?.let { if (it.contains("googleusercontent")) Thumbs.upscale(it, 544) else it },
                Modifier.size(size),
                shape = RoundedCornerShape(10.dp),
            )
            if (item.count > 0) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${item.count}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.uploader?.let {
            Text(
                (if (item.isAlbum) "Album • " else "") + it,
                style = MaterialTheme.typography.bodySmall,
                color = Freease.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ArtistCircle(artist: ArtistItem, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    Column(
        modifier.width(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.thumbnail, Modifier.size(size), shape = CircleShape)
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist.subscribers > 0) {
            Text("${Format.compact(artist.subscribers)} subscriber", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
        } else {
            Text("Artis", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
        }
    }
}

@Composable
fun ArtistRow(artist: ArtistItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artist.thumbnail, Modifier.size(56.dp), shape = CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (artist.subscribers > 0) "Artis • ${Format.compact(artist.subscribers)} subscriber" else "Artis",
                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
            )
        }
    }
}

@Composable
fun PlaylistRow(item: PlaylistItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.thumbnail, Modifier.size(56.dp), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (item.isAlbum) "Album" else "Playlist",
                    item.uploader,
                    if (item.count > 0) "${item.count} lagu" else null,
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Sampul playlist lokal: mozaik 2×2 dari lagu pertama, atau gradien bila kosong. */
@Composable
fun MosaicCover(ids: List<String>, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val accent = Freease.accent
    Box(modifier.clip(shape)) {
        when {
            ids.isEmpty() -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.primary, accent.secondary))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.QueueMusic, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f)) }
            ids.size < 4 -> Artwork(Thumbs.best(ids[0], null), Modifier.fillMaxSize(), fallback = Thumbs.fallback(ids[0]), shape = RoundedCornerShape(0.dp))
            else -> Column(Modifier.fillMaxSize()) {
                listOf(0, 2).forEach { r ->
                    Row(Modifier.weight(1f)) {
                        listOf(r, r + 1).forEach { i ->
                            Artwork(Thumbs.small(ids[i], null), Modifier.weight(1f).aspectRatio(1f), shape = RoundedCornerShape(0.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalPlaylistRow(p: PlaylistSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MosaicCover(p.covers, Modifier.size(60.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Playlist • ${p.songCount} lagu" + if (p.remoteUrl != null) " • dari YouTube" else "",
                style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
            )
        }
        if (p.pinned) Icon(Icons.Rounded.PlayArrow, null, tint = Freease.accent.primary, modifier = Modifier.size(16.dp))
    }
}

/** Tile pintasan di Beranda (2 kolom), seperti grid "baru diputar" Spotify. */
@Composable
fun ShortcutTile(title: String, modifier: Modifier = Modifier, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) { cover() }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

@Composable
fun LikedCover(modifier: Modifier = Modifier) {
    val accent = Freease.accent
    Box(
        modifier.background(Brush.linearGradient(listOf(accent.primary, accent.secondary, Color(0xFFFFB86B)))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.45f)) }
}

@Composable
fun rememberHorizontalSpacing() = remember { Arrangement.spacedBy(14.dp) }
