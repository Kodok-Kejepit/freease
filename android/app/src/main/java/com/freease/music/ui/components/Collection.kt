package com.freease.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Kerangka halaman koleksi: playlist, album, lagu disukai, unduhan. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    from: String,
    songs: List<Song>,
    colorUrl: String?,
    cover: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    playlistId: Long? = null,
    numbered: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    empty: @Composable () -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val downloaded by remember {
        actions.library.downloads().map { l -> l.filter { it.downloadState == DownloadState.DONE }.map { it.id }.toHashSet() }
    }.collectAsState(emptySet())
    val base by rememberArtworkColor(colorUrl, Freease.accent.primary.darken(0.45f))
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 520 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val playingThis = state.song != null && songs.any { it.id == state.song?.id } && actions.player.playingFrom.value == from

    if (onLoadMore != null) {
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 5
            }
        }
        LaunchedEffect(listState) { snapshotFlow { nearEnd }.filter { it }.collect { onLoadMore() } }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
        ) {
            item(key = "header") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.35f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val scale = 1f - (listState.firstVisibleItemScrollOffset / 1400f).coerceIn(0f, 0.25f)
                    cover(
                        Modifier
                            .size(230.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale }
                            .shadow(24.dp, ambientColor = Color.Black, spotColor = Color.Black),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        headerActions()
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { actions.player.play(songs, shuffle = true, from = from) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, "Acak", modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        PlayFab(
                            playing = playingThis && state.isPlaying,
                            onClick = {
                                if (playingThis) actions.player.toggle()
                                else actions.player.play(songs, from = from)
                            },
                        )
                    }
                }
            }
            when {
                loading -> item { SongListPlaceholder() }
                error != null && songs.isEmpty() -> item { ErrorState(error, onRetry) }
                songs.isEmpty() -> item { empty() }
                else -> itemsIndexed(songs, key = { i, s -> "${s.id}_$i" }) { i, s ->
                    SongRow(
                        s,
                        onClick = { actions.player.play(songs, i, from = from) },
                        index = if (numbered) i + 1 else null,
                        playlistId = playlistId,
                        downloaded = s.id in downloaded,
                    )
                }
            }
            if (loadingMore) item { SmallLoading() }
            footer()
        }

        // Bar atas: transparan di header, padat setelah digulir.
        Box(
            Modifier
                .fillMaxWidth()
                .background(base.darken(0.35f).copy(alpha = barAlpha))
                .statusBarsPadding()
                .height(56.dp),
        ) {
            IconButton(onClick = { actions.nav.popBackStack() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).graphicsLayer { alpha = barAlpha },
            )
        }
    }
}

@Composable
fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = tint, modifier = Modifier.size(26.dp)) }
}

