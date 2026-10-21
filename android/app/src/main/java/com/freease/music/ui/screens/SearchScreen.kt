package com.freease.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.freease.music.AppContainer
import com.freease.music.data.model.BrowseResult
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.SearchFilter
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.ui.components.ArtistRow
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.components.ErrorState
import com.freease.music.ui.components.FilterChipRow
import com.freease.music.ui.components.PlaylistRow
import com.freease.music.ui.components.SmallLoading
import com.freease.music.ui.components.SongListPlaceholder
import com.freease.music.ui.components.SongRow
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.appViewModel
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

data class ResultsState(
    val loading: Boolean = false,
    val items: List<BrowseResult> = emptyList(),
    val nextPage: Page? = null,
    val usedFilter: String? = null,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@UnstableApi
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val submitted = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow(SearchFilter.SONGS)
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val localMatches = MutableStateFlow<List<Song>>(emptyList())
    private val _results = MutableStateFlow(ResultsState())
    val results: StateFlow<ResultsState> = _results
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            query.debounce(220).distinctUntilChanged().filter { it.isNotBlank() && it != submitted.value }.collect { q ->
                launch { localMatches.value = c.library.searchLocal(q).take(4) }
                suggestions.value = c.youtube.suggestions(q).take(8)
            }
        }
    }

    fun submit(q: String = query.value) {
        val text = q.trim()
        if (text.isEmpty()) return
        query.value = text
        submitted.value = text
        viewModelScope.launch { c.library.addSearch(text) }
        load()
    }

    fun setFilter(f: SearchFilter) {
        if (filter.value == f) return
        filter.value = f
        load()
    }

    fun clearSubmitted() {
        submitted.value = null
    }

    private fun load() {
        val q = submitted.value ?: return
        val f = filter.value
        searchJob?.cancel()
        _results.value = ResultsState(loading = true)
        searchJob = viewModelScope.launch {
            _results.value = try {
                val page = c.youtube.search(q, f)
                ResultsState(items = page.items, nextPage = page.nextPage, usedFilter = page.usedFilter)
            } catch (e: Exception) {
                ResultsState(error = e.message ?: "Pencarian gagal")
            }
        }
    }

    fun loadMore() {
        val s = _results.value
        val q = submitted.value ?: return
        val next = s.nextPage ?: return
        val used = s.usedFilter ?: return
        if (s.loadingMore || s.loading) return
        _results.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            _results.value = try {
                val more = c.youtube.searchMore(q, used, filter.value, next)
                val merged = (s.items + more.items).distinctBy {
                    when (it) {
                        is BrowseResult.SongResult -> it.song.id
                        is BrowseResult.PlaylistResult -> it.playlist.url
                        is BrowseResult.ArtistResult -> it.artist.url
                    }
                }
                s.copy(items = merged, nextPage = more.nextPage, loadingMore = false)
            } catch (e: Exception) {
                s.copy(loadingMore = false, nextPage = null)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchScreen() {
    val actions = LocalActions.current
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val filter by vm.filter.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val local by vm.localMatches.collectAsState()
    val results by vm.results.collectAsState()
    val recentSearches by remember { actions.library.recentSearches() }.collectAsState(emptyList())
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val bottom = LocalBottomInset.current
    val typing = query.isNotBlank() && query != submitted

    fun submit(text: String) {
        focusManager.clearFocus()
        if (YouTubeRepository.isYouTubeUrl(text)) {
            actions.handleLink(text)
        } else {
            vm.submit(text)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Cari",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 12.dp),
        )
        SearchField(
            value = query,
            onValueChange = {
                vm.query.value = it
                if (it.isBlank()) vm.clearSubmitted()
            },
            onSubmit = { submit(query) },
            focusRequester = focus,
        )
        Spacer(Modifier.height(10.dp))

        when {
            typing -> LazyColumn(contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
                if (YouTubeRepository.isYouTubeUrl(query)) {
                    item {
                        SuggestionRow(Icons.Rounded.Link, "Buka link YouTube ini", onClick = { submit(query) })
                    }
                }
                items(suggestions) { s ->
                    SuggestionRow(Icons.Rounded.Search, s, onClick = { submit(s) }, onFill = { vm.query.value = "$s " })
                }
                if (local.isNotEmpty()) {
                    item {
                        Text(
                            "Di koleksimu",
                            style = MaterialTheme.typography.labelLarge,
                            color = Freease.colors.muted,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(local, key = { _, s -> "local_${s.id}" }) { i, s ->
                        SongRow(s, onClick = { actions.player.play(local, i, from = "Koleksimu") })
                    }
                }
            }

            submitted != null -> Column {
                FilterChipRow(
                    options = SearchFilter.entries.map { it.label },
                    selected = filter.ordinal,
                    onSelect = { vm.setFilter(SearchFilter.entries[it]) },
                )
                Spacer(Modifier.height(6.dp))
                ResultsList(results, filter, onMore = vm::loadMore, onRetry = { vm.submit(submitted!!) })
            }

            else -> BrowseHome(
                recent = recentSearches.map { it.query },
                onRecent = { submit(it) },
                onDeleteRecent = { q -> actions.container.appScope.launch { actions.library.deleteSearch(q) } },
                onClearRecent = { actions.container.appScope.launch { actions.library.clearSearches() } },
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color(0xFF222230))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Mau dengar apa hari ini?", color = Color(0xFF6A6A78), style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF111118), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Color(0xFF111118)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        AnimatedVisibility(value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Hapus", tint = Color(0xFF222230))
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onFill: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Freease.colors.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            onFill != null -> IconButton(onClick = onFill) {
                Icon(Icons.AutoMirrored.Rounded.CallMade, "Isi", tint = Freease.colors.muted, modifier = Modifier.size(20.dp))
            }
            onDelete != null -> IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Close, "Hapus", tint = Freease.colors.muted, modifier = Modifier.size(20.dp))
            }
            else -> Spacer(Modifier.height(48.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun BrowseHome(
    recent: List<String>,
    onRecent: (String) -> Unit,
    onDeleteRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    val actions = LocalActions.current
    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pencarian terakhir", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "Hapus semua",
                        style = MaterialTheme.typography.labelLarge,
                        color = Freease.colors.muted,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClearRecent).padding(6.dp),
                    )
                }
            }
            items(recent.take(6)) { q ->
                SuggestionRow(Icons.Rounded.History, q, onClick = { onRecent(q) }, onDelete = { onDeleteRecent(q) })
            }
        }
        item {
            Text(
                "Jelajahi semua",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp),
            )
        }
        items(Moods.chunked(2)) { pair ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { m ->
                    MoodCard(m, Modifier.weight(1f)) { actions.openCategory(m.title, m.query) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ResultsList(results: ResultsState, filter: SearchFilter, onMore: () -> Unit, onRetry: () -> Unit) {
    val actions = LocalActions.current
    val listState = rememberLazyListState()
    val bottom = LocalBottomInset.current
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd }.filter { it }.collect { onMore() }
    }
    when {
        results.loading -> SongListPlaceholder()
        results.error != null -> ErrorState(results.error, onRetry)
        results.items.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "Tidak ada hasil", "Coba kata kunci lain atau filter berbeda.")
        else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
            val songs = results.items.filterIsInstance<BrowseResult.SongResult>().map { it.song }
            itemsIndexed(results.items, key = { i, r ->
                when (r) {
                    is BrowseResult.SongResult -> "s_${r.song.id}_$i"
                    is BrowseResult.PlaylistResult -> "p_${r.playlist.url}_$i"
                    is BrowseResult.ArtistResult -> "a_${r.artist.url}_$i"
                }
            }) { _, r ->
                when (r) {
                    is BrowseResult.SongResult -> SongRow(r.song, onClick = {
                        if (filter == SearchFilter.VIDEOS) {
                            actions.player.play(songs, songs.indexOf(r.song), from = "Hasil pencarian")
                        } else {
                            actions.player.startRadio(r.song, "Hasil pencarian")
                        }
                    })
                    is BrowseResult.PlaylistResult -> PlaylistRow(r.playlist) { actions.openPlaylist(r.playlist.url) }
                    is BrowseResult.ArtistResult -> ArtistRow(r.artist) { actions.openArtist(r.artist.url) }
                }
            }
            if (results.loadingMore) item { SmallLoading() }
        }
    }
}
