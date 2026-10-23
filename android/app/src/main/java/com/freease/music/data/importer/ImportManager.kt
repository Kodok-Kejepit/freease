package com.freease.music.data.importer

import com.freease.music.data.LibraryRepository
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.SearchFilter
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.data.model.BrowseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class ImportItem(val track: SpotifyTrack, val song: Song? = null, val done: Boolean = false)

data class ImportState(
    val running: Boolean = false,
    val name: String = "",
    val items: List<ImportItem> = emptyList(),
    val truncated: Boolean = false,
    val playlistId: Long? = null,
    val error: String? = null,
) {
    val processed: Int get() = items.count { it.done }
    val found: Int get() = items.count { it.song != null }
    val finished: Boolean get() = !running && playlistId != null
}

/**
 * Impor playlist Spotify → playlist Freease. Tiap lagu dicari di YouTube (3 sekaligus), urutan asli
 * dipertahankan. Tetap berjalan walau layar impor ditutup.
 */
class ImportManager(
    private val client: OkHttpClient,
    private val youtube: YouTubeRepository,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(link: String) {
        if (job?.isActive == true) return
        _state.value = ImportState(running = true, name = "Membaca playlist…")
        job = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val ref = SpotifyReader.resolveShortLink(client, link)
                        ?: throw IllegalArgumentException("Link Spotify tidak dikenali")
                    SpotifyReader(client).read(ref)
                }
                if (list.tracks.isEmpty()) throw IllegalStateException("Playlist kosong atau bersifat pribadi")
                _state.value = ImportState(
                    running = true, name = list.name, truncated = list.truncated,
                    items = list.tracks.map { ImportItem(it) },
                )
                val gate = Semaphore(3)
                val results = list.tracks.mapIndexed { i, t ->
                    async {
                        gate.withPermit {
                            val song = runCatching { find(t) }.getOrNull()
                            update(i) { it.copy(song = song, done = true) }
                            song
                        }
                    }
                }.awaitAll()
                val songs = results.filterNotNull().distinctBy { it.id }
                val desc = "Diimpor dari Spotify" + (list.owner?.let { " · $it" } ?: "")
                val id = library.createPlaylist(list.name, desc)
                library.addToPlaylist(id, songs)
                _state.value = _state.value.copy(running = false, playlistId = id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, error = e.message ?: "Impor gagal")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ImportState()
    }

    fun reset() {
        if (job?.isActive != true) _state.value = ImportState()
    }

    private fun update(i: Int, f: (ImportItem) -> ImportItem) {
        synchronized(this) {
            val s = _state.value
            _state.value = s.copy(items = s.items.toMutableList().also { it[i] = f(it[i]) })
        }
    }

    private suspend fun find(t: SpotifyTrack): Song? {
        val q = "${t.artists.split(",").first().trim()} ${t.title}"
        val songs = youtube.search(q, SearchFilter.SONGS).items.filterIsInstance<BrowseResult.SongResult>().map { it.song }
        TrackMatcher.best(t, songs)?.let { return it }
        // Hasil "Lagu" kurang pas: coba pencarian video biasa.
        val videos = youtube.search(q, SearchFilter.VIDEOS).items.filterIsInstance<BrowseResult.SongResult>().map { it.song }
        return TrackMatcher.best(t, videos)
    }
}
