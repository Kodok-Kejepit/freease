package com.freease.music

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import com.freease.music.data.download.DownloadManager
import com.freease.music.util.CrashReporter

@UnstableApi
class FreeaseApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        DownloadManager.createChannel(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient }))
                add(com.freease.music.data.local.LocalArtFetcher.Factory(this@FreeaseApp))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(220)
            // Renderer software Robolectric (uji screenshot) tidak bisa menggambar bitmap hardware.
            .allowHardware(android.os.Build.FINGERPRINT != "robolectric")
            .build()
}
