package com.freease.music.data.mix

import com.freease.music.data.LibraryRepository
import com.freease.music.data.db.TasteEvent
import com.freease.music.data.model.Song
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.playback.smart.Taste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Satu Daily Mix: gabungan lagu yang sering kamu dengar + lagu baru yang mirip. */
data class DailyMix(
    val index: Int,
    val artists: List<String>,
    val songs: List<Song>,
) {
    val title: String get() = "Daily Mix ${index + 1}"
    val subtitle: String get() = artists.take(3).joinToString(", ") + if (artists.size > 3) " dan lainnya" else ""
}

/**
 * Pengelompokan artis dari riwayat dengar. Artis yang sering diputar berdekatan (dalam satu sesi
 * dengar) dianggap satu "selera" — begitulah tiap mix punya warna musiknya sendiri.
 */
object MixPlanner {
    data class Cluster(val artists: List<String>, val keys: Set<String>)

    private const val SESSION_GAP_MS = 30 * 60_000L

    fun plan(events: List<TasteEvent>, maxMixes: Int = 4, maxArtists: Int = 4): List<Cluster> {
        val heard = events.filter { it.playedMs >= 30_000 || (it.durationSec in 1..59 && it.playedMs >= it.durationSec * 500) }
        if (heard.isEmpty()) return emptyList()
        val count = HashMap<String, Int>()
        val display = HashMap<String, String>()
        heard.forEach {
            val k = Taste.key(it.artist)
            if (k.isEmpty()) return@forEach
            count[k] = (count[k] ?: 0) + 1
            display.putIfAbsent(k, it.artist)
        }
        // Ko-okurensi dalam satu sesi dengar.
        val cooc = HashMap<String, HashMap<String, Int>>()
        val sorted = heard.sortedBy { it.ts }
        var session = ArrayList<String>()
        fun flush() {
            val set = session.toSet()
            for (a in set) for (b in set) if (a != b) {
                val m = cooc.getOrPut(a) { HashMap() }
                m[b] = (m[b] ?: 0) + 1
            }
            session = ArrayList()
        }
        var last = 0L
        sorted.forEach { e ->
            if (last > 0 && e.ts - last > SESSION_GAP_MS) flush()
            Taste.key(e.artist).takeIf { it.isNotEmpty() }?.let { session += it }
            last = e.ts
        }
        flush()

        val remaining = count.filter { it.value >= 2 }.keys.sortedByDescending { count[it] }.toMutableList()
        val out = ArrayList<Cluster>()
        while (out.size < maxMixes && remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            if ((count[seed] ?: 0) < 3 && out.isNotEmpty()) break
            val members = arrayListOf(seed)
            while (members.size < maxArtists) {
                val next = remaining.maxByOrNull { cand -> members.sumOf { cooc[it]?.get(cand) ?: 0 } } ?: break
                if (members.sumOf { cooc[it]?.get(next) ?: 0 } < 2) break
                members += next
                remaining.remove(next)
            }
            out += Cluster(members.map { display.getValue(it) }, members.toSet())
        }
        return out
    }

    /** Selang-seling: dua lagu yang kamu kenal, satu lagu baru. */
    fun interleave(own: List<Song>, fresh: List<Song>, max: Int = 30): List<Song> {
        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        val titles = HashSet<String>()
        fun add(s: Song) {
            if (out.size < max && seen.add(s.id) && titles.add(Taste.titleKey(s.title) + "|" + Taste.key(s.artist))) out += s
        }
        var i = 0
        var j = 0
        while (out.size < max && (i < own.size || j < fresh.size)) {
            repeat(2) { if (i < own.size) add(own[i++]) }
            if (j < fresh.size) add(fresh[j++])
        }
        return out
    }
}

/** Membuat & menyimpan Daily Mix; dibuat ulang sekali sehari. */
class DailyMixRepository(
    private val file: File,
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
) {
    private val _mixes = MutableStateFlow<List<DailyMix>>(emptyList())
    val mixes: StateFlow<List<DailyMix>> = _mixes.asStateFlow()
    private var day: String? = null
    private val lock = Mutex()
    private var loaded = false

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun ensureFresh(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!loaded) {
                loaded = true
                runCatching { load() }
            }
            if (!force && day == today() && _mixes.value.isNotEmpty()) return@withContext
            val built = runCatching { build() }.getOrNull() ?: return@withContext
            if (built.isEmpty() && _mixes.value.isNotEmpty() && !force) return@withContext
            _mixes.value = built
            day = today()
            runCatching { save() }
        }
    }

    private suspend fun build(): List<DailyMix> {
        val now = System.currentTimeMillis()
        val events = library.tasteEvents(now - 60L * 24 * 3600_000, 3000)
        val liked = library.likedSongs()
        val clusters = MixPlanner.plan(events)
        if (clusters.isEmpty()) return emptyList()

        val plays = events.groupingBy { it.songId }.eachCount()
        val known = HashMap<String, Song>()
        liked.forEach { known[it.id] = it }
        for (id in plays.keys) if (id !in known) library.getSong(id)?.toSong()?.let { known[id] = it }

        return coroutineScope {
            clusters.mapIndexed { index, c ->
                async {
                    val own = known.values.filter { !it.isLive && Taste.key(it.artist) in c.keys }
                        // Lagu yang sering diputar lebih mungkin masuk, tapi tetap diacak setiap hari.
                        .sortedByDescending { (plays[it.id] ?: 0) + (if (liked.any { l -> l.id == it.id }) 2 else 0) + Math.random() * 3 }
                        .take(18)
                    val seeds = own.filter { !it.isLocal }.shuffled().take(3)
                    val fresh = seeds.map { s -> async { runCatching { youtube.related(s.id, 20) }.getOrDefault(emptyList()) } }
                        .awaitAll().flatten()
                        .filter { f -> f.id !in known && !f.isLive && f.durationSec in 60..900 }
                        .distinctBy { it.id }
                        .shuffled()
                    DailyMix(index, c.artists, MixPlanner.interleave(own.shuffled(), fresh))
                }
            }.awaitAll().filter { it.songs.size >= 8 }.mapIndexed { i, m -> m.copy(index = i) }
        }
    }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        day = o.optString("day")
        val arr = o.getJSONArray("mixes")
        _mixes.value = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val a = m.getJSONArray("artists")
            val s = m.getJSONArray("songs")
            DailyMix(
                i, (0 until a.length()).map { a.getString(it) },
                (0 until s.length()).map { k ->
                    val x = s.getJSONObject(k)
                    Song(
                        x.getString("id"), x.getString("t"), x.getString("a"),
                        artistUrl = x.optString("u").ifEmpty { null },
                        durationSec = x.optLong("d"), thumbnail = x.optString("th").ifEmpty { null },
                    )
                },
            )
        }
    }

    private fun save() {
        val arr = JSONArray()
        _mixes.value.forEach { m ->
            arr.put(
                JSONObject().put("artists", JSONArray(m.artists)).put(
                    "songs",
                    JSONArray().apply {
                        m.songs.forEach { s ->
                            put(
                                JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("u", s.artistUrl ?: "")
                                    .put("d", s.durationSec).put("th", s.thumbnail ?: ""),
                            )
                        }
                    },
                ),
            )
        }
        file.writeText(JSONObject().put("day", day).put("mixes", arr).toString())
    }
}
