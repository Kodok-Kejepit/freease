package com.freease.music.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.freease.music.AppContainer
import com.freease.music.data.model.PlaylistItem
import com.freease.music.data.model.Song
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.ErrorState
import com.freease.music.ui.components.LikedCover
import com.freease.music.ui.components.MosaicCover
import com.freease.music.ui.components.PlayingBars
import com.freease.music.ui.components.PlaylistCard
import com.freease.music.ui.components.SectionHeader
import com.freease.music.ui.components.Shimmer
import com.freease.music.ui.components.ShortcutTile
import com.freease.music.ui.components.SongCard
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.Routes
import com.freease.music.ui.navigation.appViewModel
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ArtistMixes(val artist: String, val playlists: List<PlaylistItem>)

data class HomeFeed(
    val loading: Boolean = true,
    val error: String? = null,
    val quickPicks: List<Song> = emptyList(),
    val trending: List<Song> = emptyList(),
    val fromFollowed: List<Song> = emptyList(),
    val charts: List<PlaylistItem> = emptyList(),
    val becauseYouListen: List<ArtistMixes> = emptyList(),
)

@UnstableApi
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val feed = MutableStateFlow(HomeFeed())
    var refreshing = mutableStateOf(false)

    init { load() }

    fun refresh() {
        refreshing.value = true
        load()
    }

    private fun load() = viewModelScope.launch {
        if (feed.value.trending.isEmpty()) feed.value = feed.value.copy(loading = true, error = null)
        val yt = c.youtube
        try {
            val recent = c.library.recent(5).first()
            val top = c.library.mostPlayed(10).first()
            val followed = c.library.followedArtists().first()

            val trendingJob = async { runCatching { yt.trendingMusic() }.getOrDefault(emptyList()) }
            val picksJob = async {
                val seeds = (recent.take(3) + top.take(2)).distinctBy { it.id }
                seeds.map { s -> async { runCatching { yt.related(s.id, 12) }.getOrDefault(emptyList()) } }
                    .awaitAll().flatten()
                    .distinctBy { it.id }
                    .filter { r -> recent.none { it.id == r.id } }
                    .shuffled()
                    .take(20)
            }
            val followedJob = async {
                followed.take(4).map { a ->
                    async { runCatching { yt.artist(a.url).songs.take(4) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten().shuffled()
            }
            val chartsJob = async { yt.searchPlaylists("top 50 ${c.settings.current.region} chart lagu terpopuler").take(10) }
            val becauseJob = async {
                top.map { it.artist }.distinct().take(2).map { artist ->
                    async { ArtistMixes(artist, yt.searchPlaylists("$artist mix").take(8)) }
                }.awaitAll().filter { it.playlists.isNotEmpty() }
            }

            val trending = trendingJob.await()
            var picks = picksJob.await()
            if (picks.isEmpty()) picks = trending.shuffled().take(16)
            feed.value = HomeFeed(
                loading = false,
                quickPicks = picks,
                trending = trending,
                fromFollowed = followedJob.await(),
                charts = chartsJob.await(),
                becauseYouListen = becauseJob.await(),
            )
            if (trending.isEmpty() && picks.isEmpty()) {
                feed.value = feed.value.copy(error = if (c.isOnline()) "YouTube tidak merespons. Coba lagi sebentar." else "Tidak ada koneksi internet.")
            }
        } catch (e: Exception) {
            feed.value = feed.value.copy(loading = false, error = e.message ?: "Terjadi kesalahan")
        } finally {
            refreshing.value = false
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val vm = appViewModel { HomeViewModel(it) }
    val feed by vm.feed.collectAsState()
    val recent by actions.library.recent(8).collectAsState(emptyList())
    val mostPlayed by actions.library.mostPlayed(12).collectAsState(emptyList())
    val playlists by actions.library.playlistSummaries().collectAsState(emptyList())
    val likedCount by actions.library.likedCount().collectAsState(0)
    val accent = Freease.accent

    PullToRefreshBox(isRefreshing = vm.refreshing.value, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp),
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(listOf(accent.primary.copy(alpha = 0.32f), Color.Transparent)),
                    ),
                ) {
                    Row(
                        Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Format.greeting(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { actions.navigate(Routes.STATS) }) { Icon(Icons.Rounded.BarChart, "Statistik") }
                        IconButton(onClick = { actions.navigate(Routes.HISTORY) }) { Icon(Icons.Rounded.History, "Riwayat") }
                        IconButton(onClick = { actions.navigate(Routes.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Pengaturan") }
                    }
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Moods.take(10)) { m ->
                        Box(
                            Modifier.clip(CircleShape).background(Freease.colors.card)
                                .clickable { actions.openCategory(m.title, m.query) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(m.title, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            // Pintasan 2 kolom
            item {
                val tiles = buildList<@Composable () -> Unit> {
                    add {
                        ShortcutTile("Lagu yang Disukai", Modifier.fillMaxWidth(), { actions.navigate(Routes.LIKED) }) {
                            LikedCover(Modifier.fillMaxSize())
                        }
                    }
                    playlists.take(2).forEach { p ->
                        add {
                            ShortcutTile(p.name, Modifier.fillMaxWidth(), { actions.openLocal(p.id) }) {
                                MosaicCover(p.covers, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                            }
                        }
                    }
                    recent.take(6 - size).forEach { s ->
                        add {
                            ShortcutTile(s.title, Modifier.fillMaxWidth(), { actions.player.startRadio(s, "Baru diputar") }) {
                                Artwork(s.artworkSmall, Modifier.fillMaxSize(), fallback = Thumbs.fallback(s.id), shape = RoundedCornerShape(0.dp))
                            }
                        }
                    }
                }
                if (likedCount > 0 || tiles.size > 1) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { tile -> Box(Modifier.weight(1f)) { tile() } }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (feed.loading && feed.quickPicks.isEmpty()) {
                item { HomeLoading() }
            } else if (feed.error != null && feed.quickPicks.isEmpty()) {
                item { ErrorState(feed.error!!, onRetry = vm::refresh) }
            }

            if (feed.quickPicks.isNotEmpty()) {
                item {
                    SectionHeader("Pilihan cepat", subtitle = "Dimulai dari lagu yang kamu suka", action = "Putar", onAction = {
                        actions.player.play(feed.quickPicks, from = "Pilihan cepat")
                    })
                    QuickPicksGrid(feed.quickPicks)
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    SectionHeader("Baru diputar", action = "Riwayat", onAction = { actions.navigate(Routes.HISTORY) })
                    SongCardRow(recent, "Baru diputar")
                }
            }
            if (feed.trending.isNotEmpty()) {
                item {
                    SectionHeader("Trending musik", subtitle = "Lagi ramai di YouTube", action = "Putar semua", onAction = {
                        actions.player.play(feed.trending, from = "Trending musik")
                    })
                    SongCardRow(feed.trending, "Trending musik")
                }
            }
            if (feed.fromFollowed.isNotEmpty()) {
                item {
                    SectionHeader("Dari artis yang kamu ikuti")
                    SongCardRow(feed.fromFollowed, "Artis yang kamu ikuti")
                }
            }
            if (mostPlayed.isNotEmpty()) {
                item {
                    SectionHeader("Paling sering kamu putar", action = "Statistik", onAction = { actions.navigate(Routes.STATS) })
                    SongCardRow(mostPlayed, "Paling sering diputar")
                }
            }
            feed.becauseYouListen.forEach { mix ->
                item(key = "because_${mix.artist}") {
                    SectionHeader(mix.artist, subtitle = "Karena kamu mendengarkan")
                    PlaylistCardRow(mix.playlists)
                }
            }
            if (feed.charts.isNotEmpty()) {
                item {
                    SectionHeader("Tangga lagu & playlist populer")
                    PlaylistCardRow(feed.charts)
                }
            }
            item {
                SectionHeader("Jelajahi suasana")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(Moods.drop(10)) { m -> MoodCard(m) { actions.openCategory(m.title, m.query) } }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun QuickPicksGrid(songs: List<Song>) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    LazyHorizontalGrid(
        rows = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().height(64.dp * 4),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            val current = state.song?.id == s.id
            Row(
                Modifier
                    .width(300.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { actions.player.play(songs, i, from = "Pilihan cepat") }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Artwork(s.artworkSmall, Modifier.size(52.dp), fallback = Thumbs.fallback(s.id), shape = RoundedCornerShape(6.dp))
                    if (current) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) { PlayingBars(playing = state.isPlaying, color = Color.White) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (current) Freease.accent.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SongCardRow(songs: List<Song>, from: String) {
    val actions = LocalActions.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            SongCard(s, onClick = { actions.player.play(songs, i, from = from) })
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaylistCardRow(list: List<PlaylistItem>) {
    val actions = LocalActions.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list, key = { it.url }) { p -> PlaylistCard(p, onClick = { actions.openPlaylist(p.url) }) }
    }
}

@Composable
fun MoodCard(m: Mood, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(width = 150.dp, height = 90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(m.color, m.color.copy(alpha = 0.55f))))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

@Composable
private fun HomeLoading() {
    Column(Modifier.padding(16.dp)) {
        Shimmer(Modifier.width(160.dp).height(22.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.height(14.dp))
        repeat(4) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Shimmer(Modifier.width(200.dp).height(14.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(6.dp))
                    Shimmer(Modifier.width(120.dp).height(11.dp), RoundedCornerShape(4.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { Shimmer(Modifier.size(150.dp), RoundedCornerShape(10.dp)) }
        }
    }
}
