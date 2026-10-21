package com.freease.music.playback

import android.app.PendingIntent
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        val c = container
        val settings = c.settings.current

        exo = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
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
                    .setBufferDurationsMs(30_000, 180_000, 1_000, 2_500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exo.skipSilenceEnabled = settings.skipSilence
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
        if (settings.resumeOnStart) restoreQueue()
        handler.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
            container.settings.flow.collect { s ->
                exo.skipSilenceEnabled = s.skipSilence
                container.effects.apply(s)
            }
        }
    }

    private fun observeVideoMode() {
        scope.launch {
            container.videoMode.drop(1).collect { swapCurrent(it) }
        }
    }

    private fun observeSleepTimer() {
        scope.launch {
            container.sleepTimer.state.map { it.endsAt }.distinctUntilChanged().collect { endsAt ->
                if (endsAt == null) {
                    mixer.sleep = 1f
                    sleepFading = false
                    return@collect
                }
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
        return if (s.saverOnCellular && container.isMetered()) AudioQuality.SAVER else s.audioQuality
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
            flushListening()
            trackingSong = mediaItem?.let(MediaItems::toSong)
            trackingSong?.let { song -> scope.launch { container.library.touch(song) } }

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (container.sleepTimer.state.value.endOfTrack) {
                    exo.pause()
                    container.sleepTimer.cancel()
                }
                if (container.settings.current.crossfadeSec > 0) fadeInStartedAt = System.currentTimeMillis()
            }
            mediaItem?.let { if (MediaItems.isVideo(it) != container.videoMode.value) swapCurrent(container.videoMode.value) }
            updateLikeButton()
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
            if (!isPlaying) saveQueue()
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
            if ((http != null || networkish) && tries < 2) {
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
            container.emitMessage("Lewati \"${item.mediaMetadata.title}\": $reasonText")
            if (exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
                exo.play()
            }
        }
    }

    // ------------------------------------------------------------------ ticker

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (exo.isPlaying) {
                if (lastTick > 0) listenedMs += (now - lastTick)
                lastTick = now
                updateCrossfade(now)
            } else {
                lastTick = 0
            }
            if (exo.isPlaying && now - lastSaveAt > 15_000) {
                lastSaveAt = now
                saveQueue()
            }
            handler.postDelayed(this, if (exo.isPlaying) 100 else 500)
        }
    }

    private fun updateCrossfade(now: Long) {
        val cfMs = container.settings.current.crossfadeSec * 1000L
        if (cfMs <= 0 || exo.currentMediaItem?.let(MediaItems::isLive) == true) {
            if (mixer.crossfade != 1f) mixer.crossfade = 1f
            return
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
        mixer.crossfade = minOf(fadeIn, fadeOut)
    }

    private fun flushListening() {
        val song = trackingSong ?: return
        val ms = listenedMs
        listenedMs = 0
        lastTick = 0
        val threshold = if (song.durationSec in 1..59) song.durationSec * 500 else 15_000
        if (ms >= threshold) {
            container.appScope.launch { container.library.recordPlay(song, ms) }
        }
    }

    // ------------------------------------------------------------------ autoplay

    private fun maybeAutoplay() {
        val s = container.settings.current
        if (!s.autoplay || exo.repeatMode != Player.REPEAT_MODE_OFF || exo.mediaItemCount == 0) return
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
    }
}
