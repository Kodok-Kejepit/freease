package com.freease.music.data.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.freease.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lagu dari penyimpanan HP. Id-nya "local:<id MediaStore>" supaya tidak tertukar dengan video YouTube. */
object LocalSongs {
    const val PREFIX = "local:"
    const val ART_SCHEME = "localart"

    fun isLocal(id: String) = id.startsWith(PREFIX)

    private fun mediaId(id: String) = id.removePrefix(PREFIX).toLongOrNull() ?: -1

    fun contentUri(id: String): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId(id))

    /** Alamat sampul untuk Coil (dimuat oleh [LocalArtFetcher]). */
    fun artUri(id: String) = "$ART_SCHEME://${mediaId(id)}"
}

class LocalMusicRepository(private val context: Context) {
    /** Semua lagu (≥ 30 detik) di HP, terbaru dulu. */
    suspend fun songs(): List<Song> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val out = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = LocalSongs.PREFIX + c.getLong(iId)
                val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Artis tidak diketahui"
                out += Song(
                    id = id,
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "Tanpa judul",
                    artist = artist,
                    album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING },
                    durationSec = c.getLong(iDur) / 1000,
                    thumbnail = LocalSongs.artUri(id),
                )
            }
        }
        out
    }
}

/** Memuat sampul album dari berkas lagu di HP untuk Coil ("localart://<id>"). */
class LocalArtFetcher(private val context: Context, private val mediaId: Long, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        val bmp: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } else {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
        bmp?.let { ImageFetchResult(it.asImage(), isSampled = true, dataSource = DataSource.DISK) }
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != LocalSongs.ART_SCHEME) return null
            val id = data.authority?.toLongOrNull() ?: return null
            return LocalArtFetcher(context, id, options)
        }
    }
}
