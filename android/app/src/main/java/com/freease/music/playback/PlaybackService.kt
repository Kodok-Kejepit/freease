package com.freease.music.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.freease.music.playback.loudness.NormalizingAudioProcessor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.freease.music.FreeaseApp
import com.freease.music.MainActivity
import com.freease.music.R
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.freease.music.data.prefs.AudioQuality
import com.freease.music.playback.smart.SmartMood
import com.freease.music.widget.PlayerWidget
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@UnstableApi
class PlaybackService : MediaSessionService() {
    private val container get() = (application as FreeaseApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var exo: ExoPlayer
    private lateinit var mixer: VolumeMixer
    private lateinit var lyricsDisplay: LyricsDisplay

    /** Normalisasi volume (ITU-R BS.1770) di jalur audio ExoPlayer. */
    private val normalizer = NormalizingAudioProcessor { id, lufs, sec ->
        // Cukup panjang untuk dipercaya: simpan supaya putaran berikutnya langsung pas.
        if (sec >= 30) {
            container.loudness.put(id, lufs)
            container.appScope.launch(Dispatchers.IO) { container.loudness.save() }
        }
    }
    private var session: MediaSession? = null

    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    private val retries = HashMap<String, Int>()

    // Pelacakan waktu dengar untuk riwayat & statistik.
    private var trackingSong: Song? = null
    private var listenedMs = 0L
    private var lastTick = 0L
    private var fadeInStartedAt = 0L
    private var autoplayJob: Job? = null
    private var sleepFading = false
    private var lastSaveAt = 0L
    private var tickerRunning = false
    private var pausedAt = 0L

    /** Jumlah lagu berturut-turut yang gagal diputar — rem supaya tidak loop tanpa henti. */
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        val c = container
        val settings = c.settings.current

        exo = ExoPlayer.Builder(this)
            .setRenderersFactory(
                object : DefaultRenderersFactory(this) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(normalizer))
                        .build()
                }
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(
                FreeaseMediaSourceFactory(this, c.mediaCache, c.streamClient, StreamResolver(c.youtube)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 1_000, 2_500)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exo.skipSilenceEnabled = settings.skipSilence
        applyNormalizer(settings)
        container.appScope.launch(Dispatchers.IO) { container.loudness.load() }
        exo.addListener(listener)
        mixer = VolumeMixer(exo)

        val player = FadingPlayer(exo, mixer) { container.settings.current.fadePlayPause }
        val activity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also {
                it.setSmallIcon(R.drawable.ic_notification)
            },
        )

        c.effects.attach(exo.audioSessionId, settings)
        observeSettings()
        observeVideoMode()
        observeSleepTimer()
        observeLike()
        lyricsDisplay = LyricsDisplay(this, c, scope) { exo }
        lyricsDisplay.start()
        if (settings.resumeOnStart) restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET) {
            handleWidget(intent.getStringExtra(EXTRA_CMD))
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ------------------------------------------------------------------ widget

    private var widgetArtId: String? = null
    private var widgetArt: Bitmap? = null
    private var widgetJob: Job? = null

    /** Perintah dari tombol widget layar utama. */
    private fun handleWidget(cmd: String?) {
        val p = session?.player ?: return
        when (cmd) {
            "next" -> if (exo.mediaItemCount > 0) p.seekToNextMediaItem()
            "prev" -> if (exo.mediaItemCount > 0) {
                if (exo.currentPosition > 4_000 || !exo.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
            }
            "toggle" -> when {
                exo.mediaItemCount == 0 -> restoreAndPlay()
                exo.isPlaying -> p.pause()
                else -> {
                    if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
                    if (exo.playbackState == Player.STATE_ENDED) exo.seekToDefaultPosition()
                    p.play()
                }
            }
        }
        // Service hidup hanya karena widget dan tidak ada yang diputar → jangan dibiarkan menggantung.
        handler.postDelayed({
            if (!exo.playWhenReady && session?.connectedControllers?.isEmpty() != false) stopSelf()
        }, 4_000)
    }

    private fun restoreAndPlay() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { container.queueStore.load() } ?: return@launch
            if (exo.mediaItemCount == 0) {
                exo.setMediaItems(preparePlayback(saved.songs.map(MediaItems::fromSong)), saved.index, saved.positionMs)
            }
            exo.prepare()
            session?.player?.play()
        }
    }

    /** Kirim judul/artis/sampul/status ke widget — dilewati sepenuhnya kalau tidak ada widget. */
    private fun updateWidget() {
        if (!PlayerWidget.hasWidgets(this)) return
        widgetJob?.cancel()
        val song = exo.currentMediaItem?.let(MediaItems::toSong)
        val playing = exo.isPlaying
        widgetJob = scope.launch {
            if (song != null && song.id != widgetArtId) {
                PlayerWidget.push(applicationContext, song.title, song.artist, playing, null)
                widgetArt = withContext(Dispatchers.IO) {
                    runCatching {
                        val req = ImageRequest.Builder(this@PlaybackService).data(song.artworkSmall).allowHardware(false).size(200).build()
                        (imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap()
                    }.getOrNull()
                }
                widgetArtId = song.id
            }
            PlayerWidget.push(applicationContext, song?.title, song?.artist, playing, if (song != null) widgetArt else null)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        flushListening()
        saveQueue()
        handler.removeCallbacksAndMessages(null)
        if (::lyricsDisplay.isInitialized) lyricsDisplay.stop()
        container.effects.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings

    private fun observeSettings() {
        scope.launch {
            var lastMood: String? = null
            container.settings.flow.collect { s ->
                exo.skipSilenceEnabled = s.skipSilence
                applyNormalizer(s)
                container.effects.apply(s)
                if (lastMood != null && lastMood != s.smartMood) onMoodChanged()
                lastMood = s.smartMood
            }
        }
    }

    private fun applyNormalizer(s: com.freease.music.data.prefs.AppSettings) {
        normalizer.enabled = s.normalizeVolume
        normalizer.targetLufs = when (s.normalizeLevel) {
            0 -> -19.0
            2 -> -11.0
            else -> -14.0
        }
    }

    private fun observeVideoMode() {
        scope.launch {
            container.videoMode.drop(1).collect { swapCurrent(it) }
        }
    }

    private fun observeSleepTimer() {
        // Timer berbasis lagu: ExoPlayer sendiri yang berhenti tepat di akhir lagu (juga saat ulangi-satu
        // atau lagu terakhir), tanpa sempat memutar awal lagu berikutnya.
        scope.launch {
            container.sleepTimer.state.map { it.stopAtEndOfTrack }.distinctUntilChanged().collect {
                exo.pauseAtEndOfMediaItems = it
            }
        }
        scope.launch {
            container.sleepTimer.state.map { it.endsAt }.distinctUntilChanged().collectLatest { endsAt ->
                mixer.sleep = 1f
                sleepFading = false
                if (endsAt == null) return@collectLatest
                val fadeMs = 8_000L
                delay((endsAt - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
                sleepFading = true
                val steps = 40
                for (i in steps downTo 0) {
                    mixer.sleep = i / steps.toFloat()
                    delay(fadeMs / steps)
                }
                exo.pause()
                mixer.sleep = 1f
                sleepFading = false
                container.sleepTimer.cancel()
            }
        }
    }

    private fun effectiveQuality(): AudioQuality {
        val s = container.settings.current
        if (!s.saverOnCellular || !container.isMetered()) return s.audioQuality
        // Di seluler pakai kualitas seluler, tapi tidak pernah lebih tinggi dari pilihan utama.
        return if (s.cellularQuality.ordinal >= s.audioQuality.ordinal) s.cellularQuality else s.audioQuality
    }

    private suspend fun localPath(id: String): String? = withContext(Dispatchers.IO) {
        container.db.songs().get(id)?.takeIf { it.downloadState == DownloadState.DONE }?.downloadPath
    }

    private suspend fun preparePlayback(items: List<MediaItem>): MutableList<MediaItem> {
        val video = container.videoMode.value
        val s = container.settings.current
        val q = effectiveQuality()
        return items.map { item ->
            MediaItems.forPlayback(item, video, localPath(item.mediaId), q, s.videoQuality)
        }.toMutableList()
    }

    private fun swapCurrent(video: Boolean) {
        val item = exo.currentMediaItem ?: return
        if (MediaItems.isLive(item) || MediaItems.isVideo(item) == video) return
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition
        scope.launch {
            val s = container.settings.current
            val replacement = MediaItems.forPlayback(
                item, video, if (video) null else localPath(item.mediaId), effectiveQuality(), s.videoQuality,
            )
            if (exo.currentMediaItemIndex != index || exo.currentMediaItem?.mediaId != item.mediaId) return@launch
            exo.replaceMediaItem(index, replacement)
            exo.seekTo(index, position)
        }
    }

    // ------------------------------------------------------------------ listener

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newId = mediaItem?.mediaId
            if (newId != null && newId == trackingSong?.id && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                return // item yang sama diganti (mis. mode video) — bukan lagu baru
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) container.touchInteraction()
            flushListening(reason)
            normalizer.beginTrack(newId, newId?.let(container.loudness::get))
            trackingSong = mediaItem?.let(MediaItems::toSong)
            lyricsDisplay.song.value = trackingSong
            trackingSong?.let { song -> scope.launch { container.library.touch(song) } }

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                container.sleepTimer.onTrackFinished()
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && container.settings.current.crossfadeSec > 0) {
                fadeInStartedAt = System.currentTimeMillis()
            }
            mediaItem?.let { if (MediaItems.isVideo(it) != container.videoMode.value) swapCurrent(container.videoMode.value) }
            updateLikeButton()
            updateWidget()
            maybeAutoplay()
            saveQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                maybeAutoplay()
                saveQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateWidget()
            lyricsDisplay.playing.value = isPlaying
            if (isPlaying) {
                consecutiveFailures = 0
                startTicker()
                // Habis jeda lama (mis. main game), pastikan efek suara masih sehat.
                if (System.currentTimeMillis() - pausedAt > 20_000) {
                    container.effects.rebuild(exo.audioSessionId, container.settings.current)
                }
            } else {
                pausedAt = System.currentTimeMillis()
                saveQueue()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) container.touchInteraction()
            // Berhenti tepat di akhir lagu karena timer "akhir lagu" / "setelah N lagu".
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                container.sleepTimer.onStoppedAtEnd()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            container.effects.attach(audioSessionId, container.settings.current)
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = exo.currentMediaItem ?: return
            val id = item.mediaId
            val http = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val networkish = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
            val tries = retries[id] ?: 0
            if ((http != null || networkish) && tries < 2 && consecutiveFailures < MAX_FAILURES) {
                retries[id] = tries + 1
                container.youtube.invalidate(id)
                if (error.errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) {
                    // Isi cache bisa tercampur format lain — buang supaya diambil ulang bersih.
                    item.localConfiguration?.customCacheKey?.let { key ->
                        runCatching { container.mediaCache.removeResource(key) }
                    }
                }
                exo.prepare()
                exo.play()
                return
            }
            val reasonText = when {
                http?.responseCode == 403 -> "Akses stream ditolak YouTube"
                !container.isOnline() -> "Tidak ada koneksi internet"
                else -> error.localizedMessage ?: "Gagal memutar"
            }
            consecutiveFailures++
            if (consecutiveFailures >= MAX_FAILURES || !container.isOnline()) {
                // Kemungkinan masalah jaringan/YouTube, bukan lagunya — berhenti, jangan loop.
                container.emitMessage("Pemutaran dihentikan: $reasonText. Coba lagi sebentar lagi.")
                consecutiveFailures = 0
                return
            }
            container.emitMessage("Lewati \"${item.mediaMetadata.title}\": $reasonText")
            if (exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
                exo.play()
            }
        }
    }

    // ------------------------------------------------------------------ ticker

    /**
     * Penghitung waktu dengar + crossfade. Hanya jalan selama musik berputar; tiap 1 detik,
     * dan 10×/detik hanya di detik-detik crossfade supaya volume berubah halus.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!exo.isPlaying) {
                tickerRunning = false
                lastTick = 0
                return
            }
            val now = System.currentTimeMillis()
            if (lastTick > 0) listenedMs += (now - lastTick)
            lastTick = now
            val fading = updateCrossfade(now)
            checkIdleSleep(now)
            if (now - lastSaveAt > 30_000) {
                lastSaveAt = now
                saveQueue()
            }
            handler.postDelayed(this, if (fading) 100 else 1000)
        }
    }

    private var idleJob: Job? = null

    /**
     * Tidur otomatis: kalau tidak ada sentuhan/kontrol selama batas waktu yang dipilih, volume
     * dipelankan 20 detik lalu musik dijeda. Menyentuh app/notifikasi di tengah jalan membatalkannya.
     */
    private fun checkIdleSleep(now: Long) {
        val limitMin = container.settings.current.idleSleepMin
        if (limitMin <= 0 || idleJob?.isActive == true || container.sleepTimer.state.value.active) return
        if (now - container.lastInteraction < limitMin * 60_000L) return
        idleJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val steps = 40
            for (i in steps downTo 0) {
                if (container.lastInteraction > startedAt || !exo.isPlaying) {
                    mixer.sleep = 1f
                    return@launch
                }
                mixer.sleep = i / steps.toFloat()
                delay(500)
            }
            exo.pause()
            mixer.sleep = 1f
            container.emitMessage("Musik dijeda karena lama tidak ada aktivitas")
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        lastTick = System.currentTimeMillis()
        handler.post(ticker)
    }

    /** @return true kalau sedang (atau hampir) dalam fase fade, supaya ticker dipercepat. */
    private fun updateCrossfade(now: Long): Boolean {
        val cfMs = container.settings.current.crossfadeSec * 1000L
        if (cfMs <= 0 || exo.currentMediaItem?.let(MediaItems::isLive) == true) {
            if (mixer.crossfade != 1f) mixer.crossfade = 1f
            return false
        }
        val inRamp = cfMs / 2
        val fadeIn = if (fadeInStartedAt > 0) ((now - fadeInStartedAt).toFloat() / inRamp).coerceIn(0f, 1f) else 1f
        if (fadeIn >= 1f) fadeInStartedAt = 0
        val dur = exo.duration
        val fadeOut = if (dur != C.TIME_UNSET && dur > cfMs * 2 && exo.hasNextMediaItem() &&
            exo.repeatMode != Player.REPEAT_MODE_ONE
        ) {
            ((dur - exo.currentPosition).toFloat() / cfMs).coerceIn(0f, 1f)
        } else 1f
        val value = minOf(fadeIn, fadeOut)
        if (value != mixer.crossfade) mixer.crossfade = value
        val nearEnd = dur != C.TIME_UNSET && dur - exo.currentPosition < cfMs + 1500
        return fadeInStartedAt > 0 || nearEnd
    }

    private fun flushListening(reason: Int = REASON_NONE) {
        val song = trackingSong ?: return
        val ms = listenedMs
        listenedMs = 0
        lastTick = 0
        val threshold = if (song.durationSec in 1..59) song.durationSec * 500 else 15_000
        if (ms >= threshold) {
            container.appScope.launch { container.library.recordPlay(song, ms) }
        }
        // Freease Pintar selalu belajar dari lewati/selesai, juga saat fiturnya sedang dimatikan.
        if (reason != REASON_NONE) {
            container.smart.onSongEnded(
                song, ms,
                manualSkip = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                autoEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            )
        }
    }

    // ------------------------------------------------------------------ autoplay

    private fun maybeAutoplay() {
        trimPlayedHistory()
        if (container.settings.current.smartMode) {
            maybeSmart()
            return
        }
        val s = container.settings.current
        if (!s.autoplay || exo.repeatMode != Player.REPEAT_MODE_OFF || exo.mediaItemCount == 0) return
        if (exo.mediaItemCount >= MAX_QUEUE) return
        if (exo.currentMediaItemIndex < exo.mediaItemCount - 2) return
        if (autoplayJob?.isActive == true) return
        val seed = exo.currentMediaItem?.mediaId ?: return
        autoplayJob = scope.launch {
            val existing = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }.toHashSet()
            val related = runCatching { container.youtube.related(seed) }.getOrDefault(emptyList())
                .filter { it.id !in existing }
                .distinctBy { it.title.lowercase() }
                .take(10)
            if (related.isEmpty()) return@launch
            val items = preparePlayback(related.map(MediaItems::fromSong))
            exo.addMediaItems(items)
        }
    }

    // ------------------------------------------------------------------ Freease Pintar

    private var smartJob: Job? = null
    private var smartRetryAt = 0L
    private var moodKick = false

    /** Suasana diganti: buang sisipan lama di depan lalu langsung isi dengan pilihan bersuasana baru. */
    private fun onMoodChanged() {
        smartJob?.cancel()
        smartRetryAt = 0
        val index = exo.currentMediaItemIndex
        for (i in exo.mediaItemCount - 1 downTo index + 1) {
            if (MediaItems.isSmart(exo.getMediaItemAt(i))) exo.removeMediaItem(i)
        }
        moodKick = container.settings.current.smartMode && container.settings.current.smartMood.isNotEmpty()
        maybeSmart()
    }

    /**
     * Menjaga pola "beberapa lagu pilihanmu, lalu satu lagu pilihan Freease Pintar" di
     * antrean ke depan, dan melanjutkan sebagai radio pintar saat antrean hampir habis.
     * Hanya satu permintaan dalam satu waktu, maksimal 3 sisipan di depan.
     */
    private fun maybeSmart() {
        val s = container.settings.current
        if (!s.smartMode || exo.mediaItemCount == 0 || exo.repeatMode != Player.REPEAT_MODE_OFF) return
        if (exo.currentMediaItem?.let(MediaItems::isLive) == true || exo.mediaItemCount >= MAX_QUEUE) return
        if (smartJob?.isActive == true || System.currentTimeMillis() < smartRetryAt) return

        // Urutan putar ke depan, sudah memperhitungkan mode acak.
        val timeline = exo.currentTimeline
        val order = ArrayList<Int>()
        var cursor = exo.currentMediaItemIndex
        while (order.size < 24) {
            cursor = timeline.getNextWindowIndex(cursor, exo.repeatMode, exo.shuffleModeEnabled)
            if (cursor == C.INDEX_UNSET) break
            order += cursor
        }
        val smartAhead = order.count { MediaItems.isSmart(exo.getMediaItemAt(it)) }
        val mood = SmartMood.byId(s.smartMood)
        val gap = mood?.gap ?: container.smart.session.gap

        var insertAt = -1
        var count = 1
        if (order.size <= 1) {
            if (!s.autoplay || smartAhead >= 2) return
            insertAt = exo.mediaItemCount
            count = 3
        } else if (smartAhead < 3) {
            if (exo.shuffleModeEnabled) {
                // Urutan acak tidak bisa ditentukan posisinya; cukup pastikan selalu ada satu di depan.
                if (smartAhead >= 1) return
                insertAt = exo.mediaItemCount
            } else {
                var run = 0
                for (p in 0..order.size) {
                    val smart = p < order.size && MediaItems.isSmart(exo.getMediaItemAt(order[p]))
                    if (smart) { run = 0; continue }
                    if (run >= gap) {
                        insertAt = if (p < order.size) order[p] else exo.mediaItemCount
                        break
                    }
                    if (p < order.size) run++
                }
            }
        }
        if (moodKick) {
            insertAt = exo.currentMediaItemIndex + 1
            count = 3
        }
        if (insertAt < 0) return

        val snapshot = (0 until exo.mediaItemCount).map { MediaItems.toSong(exo.getMediaItemAt(it)) }
        val index = exo.currentMediaItemIndex
        val anchorId = exo.currentMediaItem?.mediaId
        smartJob = scope.launch {
            val picks = runCatching { container.smart.pick(snapshot, index, count, mood) }.getOrDefault(emptyList())
            if (picks.isEmpty()) {
                moodKick = false
                smartRetryAt = System.currentTimeMillis() + 20_000
                return@launch
            }
            if (exo.currentMediaItem?.mediaId != anchorId) {
                handler.post { maybeSmart() } // lagu sudah berganti selama menunggu; hitung ulang
                return@launch
            }
            moodKick = false
            val items = preparePlayback(picks.map(MediaItems::fromSong))
            exo.addMediaItems(insertAt.coerceAtMost(exo.mediaItemCount), items)
        }
    }

    /**
     * Lagu yang sudah lewat jauh di belakang dibuang dari antrean (tetap ada di Riwayat),
     * supaya antrean dari putar otomatis tidak tumbuh tanpa batas selama berjam-jam.
     */
    private fun trimPlayedHistory() {
        val index = exo.currentMediaItemIndex
        if (exo.shuffleModeEnabled || index < KEEP_BEHIND * 2) return
        exo.removeMediaItems(0, index - KEEP_BEHIND)
    }

    // ------------------------------------------------------------------ queue persistence

    private fun saveQueue() {
        if (exo.mediaItemCount == 0) return
        val songs = (0 until exo.mediaItemCount).map { MediaItems.toSong(exo.getMediaItemAt(it)) }
        val q = SavedQueue(songs, exo.currentMediaItemIndex, exo.currentPosition.coerceAtLeast(0))
        container.appScope.launch(Dispatchers.IO) { container.queueStore.save(q) }
    }

    private fun restoreQueue() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { container.queueStore.load() } ?: return@launch
            if (exo.mediaItemCount > 0) return@launch
            val items = preparePlayback(saved.songs.map(MediaItems::fromSong))
            exo.setMediaItems(items, saved.index, saved.positionMs)
        }
    }

    // ------------------------------------------------------------------ like button

    private val currentId = MutableStateFlow<String?>(null)
    private var currentLiked = false

    private fun observeLike() {
        scope.launch {
            currentId.flatMapLatest { id ->
                if (id == null) flowOf(false) else container.library.isLiked(id)
            }.distinctUntilChanged().collect { liked ->
                currentLiked = liked
                updateLikeButton()
            }
        }
    }

    private fun updateLikeButton() {
        currentId.value = exo.currentMediaItem?.mediaId
        val liked = currentLiked
        run {
            val button = CommandButton.Builder(
                if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
            )
                .setDisplayName(if (liked) "Hapus dari Disukai" else "Suka")
                .setSessionCommand(likeCommand)
                .build()
            session?.setCustomLayout(ImmutableList.of(button))
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(likeCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            updateLikeButton()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_LIKE) {
                val item = exo.currentMediaItem
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return scope.future {
                    container.library.toggleLike(MediaItems.toSong(item))
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { preparePlayback(mediaItems) }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = withContext(Dispatchers.IO) { container.queueStore.load() }
                ?: throw UnsupportedOperationException("Tidak ada antrian tersimpan")
            MediaSession.MediaItemsWithStartPosition(
                preparePlayback(saved.songs.map(MediaItems::fromSong)), saved.index, saved.positionMs,
            )
        }
    }

    companion object {
        const val ACTION_LIKE = "freease.LIKE"
        const val ACTION_WIDGET = "freease.WIDGET"
        const val EXTRA_CMD = "cmd"
        private const val REASON_NONE = -1
        private const val MAX_FAILURES = 3
        private const val MAX_QUEUE = 300
        private const val KEEP_BEHIND = 30
    }
}
