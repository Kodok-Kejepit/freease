package com.freease.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.freease.music.AppContainer
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

/**
 * Pohon jelajah untuk Android Auto (dan pengendali media lain): Baru diputar, Disukai, Playlist,
 * Unduhan, Musik di HP. Lagu di dalam folder ber-id "<folder>|<videoId>", jadi saat dipilih seluruh
 * isi folder ikut masuk antrean mulai dari lagu itu.
 */
@UnstableApi
class LibraryTree(private val container: AppContainer) {
    private val searchCache = LinkedHashMap<String, List<Song>>()

    fun root(): MediaItem = folder(ROOT, "Freease")

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(RECENT, "Baru diputar"),
            folder(LIKED, "Lagu yang Disukai"),
            folder(PLAYLISTS, "Playlist"),
            folder(DOWNLOADS, "Unduhan"),
            folder(LOCAL, "Musik di HP"),
        )
        PLAYLISTS -> container.library.playlistSummaries().first().map { folder("$PLAYLIST_PREFIX${it.id}", it.name, "${it.songCount} lagu") }
        else -> songsOf(parentId).map { song(parentId, it) }
    }

    /** Isi sebuah folder lagu. */
    suspend fun songsOf(parentId: String): List<Song> = when {
        parentId == RECENT -> container.library.recent(50).first()
        parentId == LIKED -> container.library.liked().first()
        parentId == DOWNLOADS -> container.library.downloads().first().filter { it.downloadState == DownloadState.DONE }.map { it.toSong() }
        parentId == LOCAL -> runCatching { container.localMusic.songs() }.getOrDefault(emptyList())
        parentId.startsWith(PLAYLIST_PREFIX) -> parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            ?.let { container.library.playlistSongs(it).first() }.orEmpty()
        parentId.startsWith(SEARCH_PREFIX) -> synchronized(searchCache) { searchCache[parentId.removePrefix(SEARCH_PREFIX)] }.orEmpty()
        else -> emptyList()
    }

    suspend fun search(query: String): List<MediaItem> {
        val songs = withContext(Dispatchers.IO) {
            runCatching { container.youtube.searchSongs(query, 30) }.getOrDefault(emptyList())
        }
        synchronized(searchCache) {
            searchCache[query] = songs
            while (searchCache.size > 5) searchCache.remove(searchCache.keys.first())
        }
        return songs.map { song("$SEARCH_PREFIX$query", it) }
    }

    /**
     * Item yang dipilih dari pohon jelajah → antrean lengkap foldernya, mulai dari lagu itu.
     * Null kalau bukan item dari pohon (mis. dari aplikasi Freease sendiri).
     */
    suspend fun expand(items: List<MediaItem>): Pair<List<Song>, Int>? {
        val id = items.singleOrNull()?.mediaId ?: return null
        val sep = id.lastIndexOf(SEP)
        if (sep <= 0) return null
        val parent = id.substring(0, sep)
        val songId = id.substring(sep + 1)
        val songs = songsOf(parent)
        val index = songs.indexOfFirst { it.id == songId }
        return if (index < 0) null else songs to index
    }

    /** id "<folder>|<videoId>" → videoId; selain itu apa adanya. */
    fun realId(id: String): String = id.substringAfterLast(SEP)

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private fun song(parent: String, s: Song): MediaItem {
        val base = MediaItems.fromSong(s)
        val art = s.artworkUrl.takeIf { it.startsWith("http") }?.toUri()
        return base.buildUpon()
            .setMediaId("$parent$SEP${s.id}")
            .setMediaMetadata(base.mediaMetadata.buildUpon().setArtworkUri(art).build())
            .build()
    }

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val LIKED = "liked"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val LOCAL = "local"
        const val PLAYLIST_PREFIX = "pl:"
        const val SEARCH_PREFIX = "search:"
        const val SEP = '|'
    }
}

/** Sampul untuk notifikasi, layar kunci, dan Android Auto lewat Coil (termasuk sampul lagu di HP). */
@UnstableApi
class CoilBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Gambar rusak")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        val req = ImageRequest.Builder(context).data(uri.toString()).allowHardware(false).size(512).build()
        (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: error("Gagal memuat sampul")
    }
}
