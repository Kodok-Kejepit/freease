package com.freease.music.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.freease.music.AppContainer
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.YouTubeRepository
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val ARTIST = "artist?url={url}"
    const val PLAYLIST = "playlist?url={url}"
    const val LOCAL = "local/{id}"
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val MONTHLY = "monthly"
    const val SETTINGS = "settings"
    const val EQUALIZER = "equalizer"
    const val CATEGORY = "category?title={title}&query={query}"
    const val IMPORT = "import?link={link}"
    const val LOCAL_MUSIC = "local_music"
    const val CAR = "car"
    const val ALARMS = "alarms"
    const val MIX = "mix/{index}"
    const val WRAPPED = "wrapped?year={year}"
    const val RECOGNIZE = "recognize?auto={auto}"

    fun artist(url: String) = "artist?url=${Uri.encode(url)}"
    fun playlist(url: String) = "playlist?url=${Uri.encode(url)}"
    fun local(id: Long) = "local/$id"
    fun mix(index: Int) = "mix/$index"
    fun wrapped(year: Int) = "wrapped?year=$year"
    fun recognize(auto: Boolean = false) = "recognize?auto=$auto"
    fun import(link: String = "") = "import?link=${Uri.encode(link)}"
    fun category(title: String, query: String) = "category?title=${Uri.encode(title)}&query=${Uri.encode(query)}"
}

/** Permintaan menu aksi lagu; [playlistId] diisi kalau dibuka dari playlist lokal. */
data class SongMenuRequest(val song: Song, val playlistId: Long? = null, val queueIndex: Int? = null)

@Stable
@UnstableApi
class AppActions(
    val container: AppContainer,
    val nav: NavHostController,
    private val context: Context,
) {
    val player get() = container.player
    val library get() = container.library

    var menu by mutableStateOf<SongMenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var playerExpanded by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    /** Lembar izin: null = tertutup, false = hanya yang belum aktif, true = semua izin + statusnya. */
    var permissions by mutableStateOf<Boolean?>(null)

    fun toast(text: String) = container.emitMessage(text)

    fun navigate(route: String) {
        playerExpanded = false
        nav.navigate(route) { launchSingleTop = true }
    }

    fun openArtist(url: String?) {
        if (url.isNullOrBlank()) toast("Halaman artis tidak tersedia") else navigate(Routes.artist(url))
    }

    fun openPlaylist(url: String) = navigate(Routes.playlist(url))
    fun openLocal(id: Long) = navigate(Routes.local(id))
    fun openCategory(title: String, query: String) = navigate(Routes.category(title, query))

    /** Buka tab Cari dan langsung jalankan pencarian [query]. */
    fun searchFor(query: String) {
        container.pendingSearch.value = query
        playerExpanded = false
        nav.navigate(Routes.SEARCH) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun showMenu(song: Song, playlistId: Long? = null, queueIndex: Int? = null) {
        menu = SongMenuRequest(song, playlistId, queueIndex)
    }

    fun toggleLike(song: Song) {
        container.appScope.launch {
            val liked = library.toggleLike(song)
            toast(if (liked) "Ditambahkan ke Lagu yang Disukai" else "Dihapus dari Lagu yang Disukai")
        }
    }

    fun share(song: Song) = shareText("${song.title} — ${song.artist}\n${song.shareUrl}")

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Bagikan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Link YouTube (dari share/paste) → putar lagu, buka playlist, atau buka artis. */
    fun handleLink(text: String) {
        val url = YouTubeRepository.extractUrl(text) ?: return
        val yt = container.youtube
        when (yt.linkType(url)) {
            YouTubeRepository.LinkType.PLAYLIST -> openPlaylist(url)
            YouTubeRepository.LinkType.CHANNEL -> openArtist(url)
            YouTubeRepository.LinkType.VIDEO -> {
                val id = yt.videoId(url) ?: return
                toast("Memuat lagu…")
                container.appScope.launch {
                    runCatching { yt.song(id) }
                        .onSuccess { song ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                player.startRadio(song, "Link yang dibagikan")
                                playerExpanded = true
                            }
                        }
                        .onFailure { toast("Gagal membuka link: ${it.message}") }
                }
            }
            YouTubeRepository.LinkType.UNKNOWN -> toast("Link tidak dikenali")
        }
    }
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions belum disediakan") }

/** Ruang di bawah konten untuk mini player + navigasi bawah. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalActions.current.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

