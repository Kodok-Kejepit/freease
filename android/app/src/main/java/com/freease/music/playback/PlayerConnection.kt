package com.freease.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.YouTubeRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@Immutable
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playWhenReady: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val index: Int = 0,
    val isVideo: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

@Immutable
data class QueueEntry(val index: Int, val song: Song)

/** Sisi UI: MediaController yang tersambung ke [PlaybackService]. */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val youtube: YouTubeRepository,
) {
    private var future: ListenableFuture<MediaController>? = null
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    /** Label "Diputar dari …" di layar player. */
    val playingFrom = MutableStateFlow("")

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            c.addListener(listener)
            _controller.value = c
            sync(c)
            syncQueue(c)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        _controller.value?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        _controller.value = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) syncQueue(player)
        }
    }

    private fun sync(p: Player) {
        val item = p.currentMediaItem
        val size = p.videoSize
        _state.value = PlayerState(
            song = item?.let(MediaItems::toSong),
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            playWhenReady = p.playWhenReady,
            durationMs = p.duration.takeIf { it > 0 } ?: ((item?.let(MediaItems::toSong)?.durationSec ?: 0) * 1000),
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
            speed = p.playbackParameters.speed,
            pitch = p.playbackParameters.pitch,
            index = p.currentMediaItemIndex,
            isVideo = size.width > 0 && size.height > 0,
            videoWidth = size.width,
            videoHeight = size.height,
        )
    }

    private fun syncQueue(p: Player) {
        _queue.value = (0 until p.mediaItemCount).map { QueueEntry(it, MediaItems.toSong(p.getMediaItemAt(it))) }
    }

    /**
     * Posisi putar diperbarui berkala selama ada yang mengamati; melambat saat dijeda dan
     * hanya memancarkan nilai baru kalau berubah (tidak memicu recomposition sia-sia).
     */
    fun positionFlow(intervalMs: Long = 250): Flow<Long> = flow {
        while (true) {
            val c = _controller.value
            emit(c?.currentPosition ?: 0L)
            delay(if (c?.isPlaying == true) intervalMs else 1000)
        }
    }.distinctUntilChanged()

    fun bufferedFlow(intervalMs: Long = 1000): Flow<Long> = flow {
        while (true) {
            emit(_controller.value?.bufferedPosition ?: 0L)
            delay(intervalMs)
        }
    }.distinctUntilChanged()

    private val pending = ArrayList<(MediaController) -> Unit>()

    /** Jalankan aksi sekarang, atau segera setelah controller tersambung. */
    private fun withController(block: (MediaController) -> Unit) {
        val c = _controller.value
        if (c != null) {
            block(c)
        } else {
            pending += block
            connect()
        }
    }

    // ------------------------------------------------------------------ actions

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, from: String = "") {
        if (songs.isEmpty()) return
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
        playingFrom.value = from
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(songs.map(MediaItems::fromSong), start, 0)
            c.prepare()
            c.play()
        }
    }

    fun playSingle(song: Song, from: String = "") = startRadio(song, from)

    /** Putar satu lagu lalu isi antrian dengan lagu serupa (mirip "Radio lagu" Spotify). */
    fun startRadio(song: Song, from: String = "Radio ${song.title}") {
        play(listOf(song), from = from)
        scope.launch {
            val related = runCatching { youtube.related(song.id) }.getOrDefault(emptyList())
                .filter { it.id != song.id }
            if (related.isNotEmpty()) withController { it.addMediaItems(related.map(MediaItems::fromSong)) }
        }
    }

    fun playNext(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, songs.map(MediaItems::fromSong))
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) play(songs) else c.addMediaItems(songs.map(MediaItems::fromSong))
    }

    fun toggle() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { c ->
        if (c.currentPosition > 4_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun currentPosition(): Long = _controller.value?.currentPosition ?: 0L
    fun seekBy(deltaMs: Long) = withController { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }

    fun skipTo(index: Int) = withController { c ->
        c.seekTo(index, 0)
        c.play()
    }

    fun remove(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun clearUpcoming() = withController { c ->
        val next = c.currentMediaItemIndex + 1
        if (next < c.mediaItemCount) c.removeMediaItems(next, c.mediaItemCount)
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackParameters(speed: Float, pitch: Float) =
        withController { it.playbackParameters = PlaybackParameters(speed, pitch) }

    fun sendLike() = withController {
        it.sendCustomCommand(SessionCommand(PlaybackService.ACTION_LIKE, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    }

    fun currentItem(): MediaItem? = _controller.value?.currentMediaItem
}
