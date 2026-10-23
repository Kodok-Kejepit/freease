package com.freease.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.freease.music.data.LibraryRepository
import com.freease.music.data.db.FreeaseDatabase
import com.freease.music.data.download.DownloadManager
import com.freease.music.data.db.DownloadState
import com.freease.music.data.lyrics.LyricsRepository
import com.freease.music.data.lyrics.align.LyricsAutoSync
import com.freease.music.data.prefs.SettingsRepository
import com.freease.music.data.youtube.NewPipeHttp
import com.freease.music.data.youtube.StreamHeadersInterceptor
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.playback.AudioEffectsController
import com.freease.music.playback.PlayerConnection
import com.freease.music.playback.QueueStore
import com.freease.music.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Dependency container sederhana (tanpa framework DI). */
@UnstableApi
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context, appScope)

    val dataUsage = com.freease.music.data.DataUsage(context, appScope)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Interceptor jaringan: menghitung byte yang benar-benar lewat (masih terkompresi), bukan hasil ekstraknya.
        .addNetworkInterceptor(dataUsage.interceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /** Klien khusus stream (UA disesuaikan per client YouTube, timeout baca lebih longgar). */
    val streamClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(StreamHeadersInterceptor())
        .build()

    val db: FreeaseDatabase = FreeaseDatabase.build(context)
    val youtube = YouTubeRepository()
    val library = LibraryRepository(db)
    val lyrics = LyricsRepository(
        httpClient,
        File(context.filesDir, "lyrics3"),
        region = { settings.current.region },
        autoSync = LyricsAutoSync(youtube, streamClient, File(context.cacheDir, "align")) { id ->
            db.songs().get(id)?.takeIf { it.downloadState == DownloadState.DONE }?.downloadPath?.let(::File)
        },
        autoSyncEnabled = { settings.current.autoSyncLyrics && (!settings.current.saverOnCellular || !isMetered()) },
    )
    val downloads = DownloadManager(context, appScope)
    val loudness = com.freease.music.playback.loudness.LoudnessStore(File(context.filesDir, "loudness.json"))
    val importer = com.freease.music.data.importer.ImportManager(httpClient, youtube, library, appScope)
    val localMusic = com.freease.music.data.local.LocalMusicRepository(context)
    val queueStore = QueueStore(context)
    val alarms = com.freease.music.data.alarm.AlarmStore(context)

    /** Data belajar Freease Pintar (database terpisah, dibuka hanya saat pertama dipakai). */
    private val tasteDb by lazy { com.freease.music.data.taste.TasteDatabase.build(context) }
    val smart: com.freease.music.playback.smart.SmartEngine by lazy {
        com.freease.music.playback.smart.SmartEngine(library, youtube, tasteDb.dao(), appScope)
    }
    val sleepTimer = SleepTimer()
    val effects = AudioEffectsController(context)
    val eqProfiles = com.freease.music.playback.EqProfiles(context, settings, appScope) { emitMessage("Equalizer: profil ${it.label}") }
    val player = PlayerConnection(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), youtube)

    /** true = tampilkan video (HD) di player; false = audio saja (hemat data & baterai). */
    val videoMode = MutableStateFlow(false)

    /** true selama layar Freease terlihat (untuk menyembunyikan lirik melayang). */
    val appVisible = MutableStateFlow(false)

    val mediaCache: SimpleCache by lazy {
        val mb = settings.current.cacheSizeMb
        val evictor = if (mb <= 0) NoOpCacheEvictor() else LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L)
        SimpleCache(File(context.cacheDir, "media"), evictor, StandaloneDatabaseProvider(context))
    }

    /** Waktu terakhir pengguna menyentuh app atau mengendalikan pemutar (untuk tidur otomatis). */
    @Volatile var lastInteraction: Long = System.currentTimeMillis()
        private set

    fun touchInteraction() {
        lastInteraction = System.currentTimeMillis()
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    fun emitMessage(text: String) {
        _messages.tryEmit(text)
    }

    init {
        YouTubeRepository.initNewPipe(NewPipeHttp(httpClient), settings.current.region)
        eqProfiles.start()
        // Jadwal unduhan otomatis mengikuti pengaturan (aktif hanya saat mengisi daya + Wi-Fi).
        appScope.launch {
            settings.flow.map { it.autoDownload }.distinctUntilChanged().collect {
                com.freease.music.data.download.AutoDownloader.sync(context, it)
            }
        }
    }

    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
