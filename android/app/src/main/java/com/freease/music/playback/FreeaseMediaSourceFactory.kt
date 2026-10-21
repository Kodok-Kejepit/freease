package com.freease.music.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.freease.music.data.prefs.AudioQuality
import okhttp3.OkHttpClient

/**
 * - audio  : progresif lewat cache (diputar ulang tanpa unduh ulang, tetap jalan saat sinyal putus)
 * - video  : video-only (HD) + audio terbaik digabung (MergingMediaSource)
 * - live   : HLS
 * - lokal  : berkas hasil unduhan
 */
@UnstableApi
class FreeaseMediaSourceFactory(
    context: Context,
    cache: Cache,
    client: OkHttpClient,
    resolver: StreamResolver,
) : MediaSource.Factory {
    private val network = OkHttpDataSource.Factory(client)
    private val resolving = ResolvingDataSource.Factory(network, resolver)
    private val cached = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(resolving)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val progressive = ProgressiveMediaSource.Factory(cached)
        .setContinueLoadingCheckIntervalBytes(256 * 1024)
    private val hls = HlsMediaSource.Factory(resolving).setAllowChunklessPreparation(true)
    private val local = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        local.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        local.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return local.createMediaSource(mediaItem)
        if (uri.scheme != MediaItems.SCHEME) return local.createMediaSource(mediaItem)
        val id = uri.lastPathSegment ?: return local.createMediaSource(mediaItem)
        return when (uri.host) {
            MediaItems.HOST_LIVE -> hls.createMediaSource(mediaItem)
            MediaItems.HOST_VIDEO -> {
                val q = uri.getQueryParameter("q")?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() }
                    ?: AudioQuality.MAX
                val h = uri.getQueryParameter("h")?.toIntOrNull() ?: 720
                // Sumber pertama menentukan MediaItem di timeline → pakai item audio yang
                // membawa metadata lagu, video-only sebagai sumber kedua.
                val audioItem = mediaItem.buildUpon()
                    .setUri(MediaItems.audioUri(id, q) + "&v=1")
                    .setCustomCacheKey(MediaItems.audioKey(id, q))
                    .build()
                val videoItem = MediaItem.Builder()
                    .setMediaId("$id#video")
                    .setUri(MediaItems.videoUri(id, h))
                    .setCustomCacheKey(MediaItems.videoKey(id, h))
                    .build()
                MergingMediaSource(
                    true,
                    true,
                    progressive.createMediaSource(audioItem),
                    progressive.createMediaSource(videoItem),
                )
            }
            else -> progressive.createMediaSource(mediaItem)
        }
    }
}
