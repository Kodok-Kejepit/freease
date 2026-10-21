package com.freease.music.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.freease.music.AppContainer
import com.freease.music.data.model.ArtistItem
import com.freease.music.data.model.PlaylistItem
import com.freease.music.data.model.Song
import com.freease.music.data.model.Thumbs
import com.freease.music.data.youtube.ArtistPage
import com.freease.music.data.youtube.PlaylistPage
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.CollectionScreen
import com.freease.music.ui.components.ErrorState
import com.freease.music.ui.components.HeaderIcon
import com.freease.music.ui.components.PlayFab
import com.freease.music.ui.components.SectionHeader
import com.freease.music.ui.components.SmallLoading
import com.freease.music.ui.components.SongListPlaceholder
import com.freease.music.ui.components.SongRow
import com.freease.music.ui.components.darken
import com.freease.music.ui.components.rememberArtworkColor
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.appViewModel
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// ====================================================================== Artist

data class ArtistUi(
    val loading: Boolean = true,
    val page: ArtistPage? = null,
    val songs: List<Song> = emptyList(),
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@UnstableApi
class ArtistViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    val ui = MutableStateFlow(ArtistUi())

    init { load() }

    fun load() = viewModelScope.launch {
        ui.value = ArtistUi(loading = true)
        ui.value = try {
            val p = c.youtube.artist(url)
            ArtistUi(loading = false, page = p, songs = p.songs)
        } catch (e: Exception) {
            ArtistUi(loading = false, error = e.message ?: "Gagal memuat artis")
        }
    }

    fun more() {
        val s = ui.value
        val p = s.page ?: return
        val tab = p.songsTab ?: return
        val next = p.songsNext ?: return
        if (s.loadingMore) return
        ui.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            ui.value = runCatching { c.youtube.artistMore(tab, next) }.fold(
                onSuccess = { (songs, n) ->
                    s.copy(songs = (s.songs + songs).distinctBy { it.id }, page = p.copy(songsNext = n), loadingMore = false)
                },
                onFailure = { s.copy(page = p.copy(songsNext = null), loadingMore = false) },
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ArtistScreen(url: String) {
    val actions = LocalActions.current
    val vm = appViewModel(key = "artist_$url") { ArtistViewModel(it, url) }
    val ui by vm.ui.collectAsState()
    val following by remember(url) { actions.library.isFollowing(url) }.collectAsState(false)
    val player by actions.player.state.collectAsState()
    val listState = rememberLazyListState()
    val page = ui.page
    val base by rememberArtworkColor(page?.avatar, Freease.accent.primary.darken(0.5f))
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val scope = rememberCoroutineScope()
    val from = "Artis: ${page?.name ?: ""}"
    val playingThis = player.song != null && ui.songs.any { it.id == player.song?.id } && actions.player.playingFrom.value == from

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(listState) { snapshotFlow { nearEnd }.filter { it }.collect { vm.more() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                    Artwork(
                        page?.avatar,
                        Modifier.fillMaxSize().graphicsLayer {
                            translationY = listState.firstVisibleItemScrollOffset * 0.5f
                            alpha = 1f - (listState.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 1f)
                        },
                        shape = RoundedCornerShape(0.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, base.copy(alpha = 0.4f), MaterialTheme.colorScheme.background)),
                        ),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(
                            page?.name ?: "",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 46.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if ((page?.subscribers ?: -1) > 0) {
                            Text(
                                "${Format.compact(page!!.subscribers)} subscriber",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
            if (page != null) {
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = if (following) 0.9f else 0.4f), RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        if (following) actions.library.unfollow(url)
                                        else actions.library.follow(ArtistItem(url, page.name, page.avatar, page.subscribers))
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) { Text(if (following) "Mengikuti" else "Ikuti", style = MaterialTheme.typography.labelLarge) }
                        IconButton(onClick = { ui.songs.firstOrNull()?.let { actions.player.startRadio(it, "Radio ${page.name}") } }) {
                            Icon(Icons.Rounded.Radio, "Radio artis")
                        }
                        IconButton(onClick = { actions.shareText("${page.name} di Freease\n$url") }) {
                            Icon(Icons.Rounded.Share, "Bagikan")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { actions.player.play(ui.songs, shuffle = true, from = from) }) {
                            Icon(Icons.Rounded.Shuffle, "Acak", modifier = Modifier.size(28.dp))
                        }
                        PlayFab(playing = playingThis && player.isPlaying, onClick = {
                            if (playingThis) actions.player.toggle() else actions.player.play(ui.songs, from = from)
                        })
                    }
                }
            }
            when {
                ui.loading -> item { SongListPlaceholder() }
                ui.error != null -> item { ErrorState(ui.error!!, onRetry = { vm.load() }) }
                page != null -> {
                    page.sections.forEach { section ->
                        item {
                            SectionHeader(section.title)
                            PlaylistCardRow(section.playlists)
                        }
                    }
                    item(key = "songs_header") { SectionHeader("Lagu & video") }
                    itemsIndexed(ui.songs, key = { i, s -> "${s.id}_$i" }) { i, s ->
                        SongRow(s, onClick = { actions.player.play(ui.songs, i, from = from) }, index = i + 1)
                    }
                    if (ui.loadingMore) item { SmallLoading() }
                    page.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item(key = "about") {
                            SectionHeader("Tentang")
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Freease.colors.muted,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().background(base.darken(0.4f).copy(alpha = barAlpha)).statusBarsPadding().height(56.dp),
        ) {
            IconButton(
                onClick = { actions.nav.popBackStack() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f * (1 - barAlpha))),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
            Text(
                page?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = barAlpha },
            )
        }
    }
}

// ====================================================================== Online playlist / album

data class PlaylistUi(
    val loading: Boolean = true,
    val page: PlaylistPage? = null,
    val songs: List<Song> = emptyList(),
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@UnstableApi
class OnlinePlaylistViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    val ui = MutableStateFlow(PlaylistUi())
    val saving = MutableStateFlow(false)

    init { load() }

    fun load() = viewModelScope.launch {
        ui.value = PlaylistUi(loading = true)
        ui.value = try {
            val p = c.youtube.playlist(url)
            PlaylistUi(loading = false, page = p, songs = p.songs)
        } catch (e: Exception) {
            PlaylistUi(loading = false, error = e.message ?: "Gagal memuat playlist")
        }
    }

    fun more() {
        val s = ui.value
        val p = s.page ?: return
        val next = p.nextPage ?: return
        if (s.loadingMore) return
        ui.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            ui.value = runCatching { c.youtube.playlistMore(url, next) }.fold(
                onSuccess = { (songs, n) -> s.copy(songs = (s.songs + songs).distinctBy { it.id }, page = p.copy(nextPage = n), loadingMore = false) },
                onFailure = { s.copy(page = p.copy(nextPage = null), loadingMore = false) },
            )
        }
    }

    fun save(onDone: (Long) -> Unit) = viewModelScope.launch {
        val p = ui.value.page ?: return@launch
        saving.value = true
        try {
            val full = c.youtube.playlistAll(url)
            val id = c.library.importPlaylist(p.name, url, full.songs)
            onDone(id)
        } catch (e: Exception) {
            c.emitMessage("Gagal menyimpan: ${e.message}")
        } finally {
            saving.value = false
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun OnlinePlaylistScreen(url: String) {
    val actions = LocalActions.current
    val vm = appViewModel(key = "pl_$url") { OnlinePlaylistViewModel(it, url) }
    val ui by vm.ui.collectAsState()
    val saving by vm.saving.collectAsState()
    var savedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(url) { savedId = actions.library.remotePlaylistId(url) }
    val page = ui.page
    val isAlbum = url.contains("OLAK5uy_")
    val cover = page?.thumbnail?.let { if (it.contains("googleusercontent")) Thumbs.upscale(it, 544) else it }
        ?: ui.songs.firstOrNull()?.artworkUrl

    CollectionScreen(
        title = page?.name ?: "",
        subtitle = listOfNotNull(
            if (isAlbum) "Album" else "Playlist",
            page?.uploader,
            page?.count?.takeIf { it > 0 }?.let { "$it lagu" },
        ).joinToString(" • "),
        from = page?.name ?: "Playlist",
        songs = ui.songs,
        colorUrl = cover,
        cover = { m -> Artwork(cover, m, shape = RoundedCornerShape(10.dp)) },
        loading = ui.loading,
        error = ui.error,
        onRetry = { vm.load() },
        numbered = isAlbum,
        loadingMore = ui.loadingMore,
        onLoadMore = vm::more,
        headerActions = {
            if (saving) {
                SmallLoading(Modifier.size(48.dp))
            } else {
                HeaderIcon(
                    if (savedId != null) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                    "Simpan ke koleksi",
                    tint = if (savedId != null) Freease.accent.primary else Color.White,
                ) {
                    vm.save { id ->
                        savedId = id
                        actions.toast("Disimpan ke Koleksi Kamu")
                    }
                }
            }
            HeaderIcon(Icons.Rounded.Download, "Unduh") {
                actions.container.downloads.download(ui.songs)
                actions.toast("Mengunduh ${ui.songs.size} lagu")
            }
            HeaderIcon(Icons.Rounded.Share, "Bagikan") { actions.shareText("${page?.name}\n$url") }
        },
    )
}

// ====================================================================== Category (genre/mood)

data class CategoryUi(
    val loading: Boolean = true,
    val playlists: List<PlaylistItem> = emptyList(),
    val songs: List<Song> = emptyList(),
    val error: String? = null,
)

@UnstableApi
class CategoryViewModel(private val c: AppContainer, private val query: String) : ViewModel() {
    val ui = MutableStateFlow(CategoryUi())

    init { load() }

    fun load() = viewModelScope.launch {
        ui.value = CategoryUi(loading = true)
        val playlists = runCatching { c.youtube.searchPlaylists(query) }.getOrDefault(emptyList())
        val songs = runCatching { c.youtube.searchSongs(query, 30) }.getOrDefault(emptyList())
        ui.value = CategoryUi(
            loading = false,
            playlists = playlists,
            songs = songs,
            error = if (playlists.isEmpty() && songs.isEmpty()) "Konten tidak ditemukan" else null,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CategoryScreen(title: String, query: String) {
    val actions = LocalActions.current
    val vm = appViewModel(key = "cat_$query") { CategoryViewModel(it, query) }
    val ui by vm.ui.collectAsState()
    val color = Moods.firstOrNull { it.title == title }?.color ?: Freease.accent.primary

    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.3f), Color.Transparent)))
                    .statusBarsPadding(),
            ) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
                Text(
                    title,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 20.dp),
                )
            }
        }
        when {
            ui.loading -> item { SongListPlaceholder() }
            ui.error != null -> item { ErrorState(ui.error!!, onRetry = { vm.load() }) }
            else -> {
                if (ui.playlists.isNotEmpty()) {
                    item {
                        SectionHeader("Playlist $title")
                        PlaylistCardRow(ui.playlists)
                    }
                }
                if (ui.songs.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("Lagu pilihan", Modifier.weight(1f))
                            PlayFab(false, { actions.player.play(ui.songs, from = title) }, Modifier.padding(end = 16.dp, top = 12.dp), size = 46.dp)
                        }
                    }
                    itemsIndexed(ui.songs, key = { i, s -> "${s.id}_$i" }) { i, s ->
                        SongRow(s, onClick = { actions.player.play(ui.songs, i, from = title) })
                    }
                }
            }
        }
    }
}

