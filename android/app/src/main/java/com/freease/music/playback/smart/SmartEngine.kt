package com.freease.music.playback.smart

import com.freease.music.data.LibraryRepository
import com.freease.music.data.model.Song
import com.freease.music.data.taste.TasteDao
import com.freease.music.data.youtube.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Otak Freease Pintar. Semuanya berjalan di perangkat: belajar dari riwayat dengar, lagu
 * disukai, serta lewati/selesai/jempol; kandidat diambil dari lagu terkait di YouTube lalu
 * dinilai dengan [SmartScorer]. Tidak ada proses yang berjalan terus — hanya bekerja saat
 * service meminta sisipan.
 */
class SmartEngine(
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val dao: TasteDao,
    private val scope: CoroutineScope,
) {
    val session = SmartSession()

    @Volatile private var cached: TasteProfile? = null
    @Volatile private var builtAt = 0L
    private val lock = Mutex()

    private suspend fun profile(): TasteProfile = lock.withLock {
        val now = System.currentTimeMillis()
        val hit = cached
        if (hit != null && now - builtAt < PROFILE_TTL_MS) return@withLock hit
        val built = withContext(Dispatchers.IO) {
            TasteProfile.build(
                now = now,
                events = library.tasteEvents(now - 90L * 24 * 3600_000),
                liked = library.likedSongs(),
                songSignals = dao.songs(),
                artistSignals = dao.artists(),
                hour = Taste.hourOf(now),
            )
        }
        cached = built
        builtAt = now
        built
    }

    private fun invalidate() { builtAt = 0L }

    /**
     * Pilih [n] lagu untuk disisipkan. [queue] adalah antrean sekarang (urutan timeline) dan
     * [currentIndex] posisi lagu yang sedang diputar.
     */
    suspend fun pick(
        queue: List<Song>,
        currentIndex: Int,
        n: Int,
        mood: SmartMood? = null,
        playlistWide: Boolean = false,
    ): List<Song> = withContext(Dispatchers.Default) {
        val current = queue.getOrNull(currentIndex) ?: return@withContext emptyList()
        val profile = profile()

        // Seed: lagu sekarang + dua lagu sebelumnya + satu jangkar dari lagu yang disukai.
        val seeds = LinkedHashMap<String, Pair<Song, Double>>()
        seeds[current.id] = current to 1.0
        for (k in 1..2) {
            val prev = queue.getOrNull(currentIndex - k) ?: continue
            if (!prev.isLive) seeds.putIfAbsent(prev.id, prev to (if (k == 1) 0.7 else 0.5))
        }
        if (playlistWide) {
            // Smart Shuffle: rekomendasi mewakili seluruh playlist, bukan hanya lagu yang kebetulan sedang diputar.
            queue.filter { !it.isSmartPick && !it.isLive && it.id !in seeds && !it.isLocal }.shuffled().take(3)
                .forEach { seeds[it.id] = it to 0.8 }
        } else {
            profile.liked.filter { it.id !in seeds && it.artist != current.artist }.randomOrNull()
                ?.let { seeds[it.id] = it to 0.6 }
        }

        val related = coroutineScope {
            seeds.values.map { (song, weight) ->
                async { Triple(song, weight, runCatching { youtube.related(song.id, 25) }.getOrDefault(emptyList())) }
            }.awaitAll()
        }

        class Acc(val song: Song, var score: Double, var best: Double, var bestTitle: String?)
        val acc = LinkedHashMap<String, Acc>()
        for ((seed, weight, list) in related) {
            list.forEachIndexed { rank, song ->
                val contribution = weight * (1.0 - rank / 30.0).coerceAtLeast(0.1)
                val a = acc.getOrPut(song.id) { Acc(song, 0.0, 0.0, null) }
                a.score += contribution
                if (contribution > a.best) { a.best = contribution; a.bestTitle = seed.title }
            }
        }
        val candidates = ArrayList<Candidate>(acc.size + 48)
        acc.values.mapTo(candidates) { Candidate(it.song, it.score.coerceAtMost(1.0), it.bestTitle) }

        // Suasana aktif: tambah kandidat dari pencarian bertema agar tidak hanya bergantung pada lagu terkait.
        if (mood != null) {
            val found = coroutineScope {
                mood.queries.shuffled().take(2).map { q ->
                    async { runCatching { youtube.searchSongs(q, 20) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }
            found.forEach { list ->
                list.forEachIndexed { rank, song ->
                    candidates += Candidate(song, (0.55 - rank * 0.01).coerceAtLeast(0.2), null)
                }
            }
        }

        // Sesekali munculkan lagu favorit lama yang belum diputar belakangan.
        profile.liked.filter { it.id !in profile.playedLatelyIds }.shuffled().take(6)
            .mapTo(candidates) { Candidate(it, 0.3, null, fromLiked = true) }

        val tail = (currentIndex - 1..currentIndex + 3).mapNotNull { queue.getOrNull(it) }.map { Taste.key(it.artist) }
        val ctx = PickContext(
            queueIds = queue.mapTo(HashSet()) { it.id },
            tailArtists = tail,
            explore = session.explore,
            sessionArtistSkips = HashMap(session.artistSkips),
            mood = mood,
        )
        SmartScorer.choose(candidates, profile, ctx, n)
    }

    /** Dipanggil service tiap kali satu lagu selesai/ditinggalkan. */
    fun onSongEnded(song: Song, listenedMs: Long, manualSkip: Boolean, autoEnd: Boolean) {
        if (song.isLive) return
        val outcome = Taste.classify(song.durationSec, listenedMs, manualSkip, autoEnd) ?: return
        val skipped = outcome == Taste.Outcome.SKIP
        val key = Taste.key(song.artist)
        session.noteArtist(key, skipped)
        if (song.isSmartPick) session.record(!skipped)
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.bump(song.id, key, if (skipped) 1 else 0, if (skipped) 0 else 1, 0, 0, System.currentTimeMillis())
            }
        }
    }

    /** Jempol naik pada lagu sisipan: otomatis masuk Lagu yang Disukai dan menguatkan artisnya. */
    fun thumbUp(song: Song) {
        session.record(true)
        session.noteArtist(Taste.key(song.artist), skipped = false)
        scope.launch(Dispatchers.IO) {
            runCatching {
                library.setLiked(song.copy(smartWhy = null), true)
                dao.bump(song.id, Taste.key(song.artist), 0, 0, 1, 0, System.currentTimeMillis())
            }
            invalidate()
        }
    }

    /** Jempol turun: lagu tidak akan ditawarkan lagi dan artisnya dikurangi bobotnya. */
    fun thumbDown(song: Song) {
        session.record(false)
        session.noteArtist(Taste.key(song.artist), skipped = true)
        scope.launch(Dispatchers.IO) {
            runCatching { dao.bump(song.id, Taste.key(song.artist), 0, 0, 0, 1, System.currentTimeMillis()) }
            invalidate()
        }
    }

    /** Lupakan semua yang dipelajari (riwayat putar dan lagu disukai tidak disentuh). */
    fun reset() {
        session.reset()
        scope.launch(Dispatchers.IO) {
            runCatching { dao.clearSongs(); dao.clearArtists() }
            invalidate()
        }
    }

    private companion object {
        const val PROFILE_TTL_MS = 10 * 60_000L
    }
}
