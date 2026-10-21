package com.freease.music.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.freease.music.data.prefs.AudioQuality
import com.freease.music.data.youtube.YouTubeRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Mengubah "freease://..." menjadi URL stream googlevideo saat ExoPlayer mulai membaca. */
@UnstableApi
class StreamResolver(private val youtube: YouTubeRepository) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != MediaItems.SCHEME) return dataSpec
        val id = uri.lastPathSegment ?: throw IOException("ID video kosong")
        val url = try {
            runBlocking { resolve(uri, id) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gagal mengambil stream: ${e.message}", e)
        }
        return dataSpec.withUri(Uri.parse(url))
    }

    private suspend fun resolve(uri: Uri, id: String): String {
        val info = youtube.streamInfo(id)
        return when (uri.host) {
            MediaItems.HOST_AUDIO -> {
                val q = uri.getQueryParameter("q")?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
                    ?: AudioQuality.MAX
                youtube.pickAudio(info, q)?.url ?: throw IOException("Stream audio tidak tersedia")
            }
            MediaItems.HOST_VIDEO_ONLY -> {
                val h = uri.getQueryParameter("h")?.toIntOrNull() ?: 720
                youtube.pickVideo(info, h)?.url ?: throw IOException("Stream video tidak tersedia")
            }
            MediaItems.HOST_LIVE -> info.hlsUrl?.takeIf { it.isNotBlank() }
                ?: throw IOException("Siaran langsung tidak tersedia")
            else -> throw IOException("URI tidak dikenal: $uri")
        }
    }
}
