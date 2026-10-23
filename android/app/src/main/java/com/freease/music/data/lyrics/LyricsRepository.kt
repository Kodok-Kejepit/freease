package com.freease.music.data.lyrics

import com.freease.music.data.lyrics.align.AutoSyncResult
import com.freease.music.data.lyrics.align.LyricsAutoSync
import com.freease.music.data.lyrics.align.alignedWith
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.LyricWord
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed interface LyricsState {
    data object Loading : LyricsState
    /** [syncing] = sedang diselaraskan otomatis dengan suara video (lirik belum mengikuti lagu). */
    data class Ready(val lyrics: Lyrics, val syncing: Boolean = false) : LyricsState

    /** Gagal karena jaringan; bukan berarti liriknya tidak ada. */
    data object Failed : LyricsState
}

/**
 * Satu sumber kebenaran lirik per video. Kartu lirik di player dan layar lirik penuh mengamati
 * [state] yang sama, jadi keduanya selalu menampilkan hal yang sama. Satu video hanya dicari
 * sekali dalam satu waktu; hasil bagus tidak pernah ditimpa hasil kosong.
 */
class LyricsRepository(
    private val finder: LyricsFinder,
    private val dir: File?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Penyelaras berbasis suara (video id, id rekaman acuan, rentang lirik di acuan). Null = tidak dipakai. */
    private val autoSync: (suspend (String, String, LongRange) -> AutoSyncResult)? = null,
    private val autoSyncEnabled: () -> Boolean = { true },
) {
    constructor(
        client: OkHttpClient,
        dir: File?,
        region: () -> String,
        autoSync: LyricsAutoSync?,
        autoSyncEnabled: () -> Boolean,
    ) : this(
        LyricsFinder(YouTubeMusicLyrics(client, region), LrcLib(client)),
        dir,
        autoSync = autoSync?.let { it::align },
        autoSyncEnabled = autoSyncEnabled,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()

    /** Hasil di memori (termasuk "tidak ada lirik", supaya tidak dicari ulang terus). */
    private class Entry(val lyrics: Lyrics, val metaKey: String, val at: Long)

    private val memory = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MEMORY_SIZE
    }
    private val flows = LinkedHashMap<String, MutableStateFlow<LyricsState>>()
    private val inflight = HashMap<String, Deferred<LyricsState>>()

    /** Video yang sedang diselaraskan otomatis, dan kapan boleh dicoba lagi setelah gagal sementara. */
    private val syncing = HashSet<String>()
    private val syncRetryAt = HashMap<String, Long>()

    init {
        // Simpanan versi lama berisi lirik hasil pencocokan lama yang bisa salah versi.
        scope.launch { dir?.parentFile?.let { p -> OLD_DIRS.forEach { File(p, it).deleteRecursively() } } }
    }

    /** Status lirik video [id]; aliran yang sama untuk semua pengamat. */
    fun state(id: String): StateFlow<LyricsState> = synchronized(lock) {
        flows[id]?.let { return it }
        val initial = memory[id]?.let { LyricsState.Ready(it.lyrics, syncing = id in syncing) } ?: LyricsState.Loading
        MutableStateFlow(initial).also {
            flows[id] = it
            trimFlows()
        }
    }

    /** Pastikan lirik [song] dimuat. Aman dipanggil berkali-kali; tidak mengulang pencarian yang sudah beres. */
    fun ensure(song: Song) {
        val needs = synchronized(lock) {
            val cur = memory[song.id]
            when {
                inflight.containsKey(song.id) -> false
                cur == null -> true
                !cur.lyrics.isEmpty -> false
                // "Tidak ada lirik": cari ulang kalau judul/artis/durasi berubah atau sudah lama.
                else -> cur.metaKey != metaKey(song) || clock() - cur.at > EMPTY_TTL_MS
            }
        }
        if (needs) {
            load(song, force = false)
        } else {
            synchronized(lock) { memory[song.id]?.lyrics }?.let { maybeAutoSync(song.id, it) }
        }
    }

    /** Coba lagi setelah gagal jaringan. */
    fun retry(song: Song) {
        load(song, force = false)
    }

    /** Cari ulang dari internet (mis. liriknya terasa salah). Lirik lama tetap dipakai kalau jaringan gagal. */
    fun refresh(song: Song) {
        load(song, force = true)
    }

    /** Untuk pengunduh: tunggu sampai lirik tersimpan (dipakai offline nanti). */
    suspend fun get(song: Song): Lyrics {
        synchronized(lock) { memory[song.id] }?.takeIf { !it.lyrics.isEmpty }?.let { return it.lyrics }
        return (load(song, force = false).await() as? LyricsState.Ready)?.lyrics ?: Lyrics(null, null)
    }

    /**
     * Selaraskan lirik: [offsetMs] positif = lirik muncul lebih lambat. [userSynced] menandai pengguna sudah
     * mencocokkan sendiri, sehingga lirik versi lain (video klip) boleh mengikuti lagu.
     */
    fun setSync(id: String, offsetMs: Long, userSynced: Boolean) {
        val next = synchronized(lock) {
            val cur = memory[id] ?: return
            val l = cur.lyrics.copy(offsetMs = offsetMs, userSynced = userSynced)
            memory[id] = Entry(l, cur.metaKey, cur.at)
            flows[id]?.value = LyricsState.Ready(l)
            l
        }
        scope.launch { writeDisk(id, next) }
    }

    private fun load(song: Song, force: Boolean): Deferred<LyricsState> = synchronized(lock) {
        inflight[song.id]?.let { return it }
        val id = song.id
        val key = metaKey(song)
        val previous = memory[id]?.lyrics
        if (force || previous == null || previous.isEmpty) flows[id]?.value = LyricsState.Loading
        val job = scope.async {
            val result: LyricsState = try {
                val disk = if (force) null else readDisk(id)
                val found = disk ?: finder.find(song).let { fresh ->
                    // Sinkron buatan pengguna tetap dipakai kalau liriknya sama persis.
                    if (previous != null && previous.synced == fresh.synced) {
                        fresh.copy(offsetMs = previous.offsetMs, userSynced = previous.userSynced)
                    } else {
                        fresh
                    }
                }
                LyricsState.Ready(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Jaringan putus atau balasan aneh: tampilkan "gagal, coba lagi", jangan "tidak ada lirik".
                LyricsState.Failed
            }
            publish(id, key, result, previous)
        }
        inflight[id] = job
        job.invokeOnCompletion { synchronized(lock) { if (inflight[id] === job) inflight.remove(id) } }
        job
    }

    /** Simpan & umumkan hasil. Lirik yang sudah ada tidak diganti oleh kegagalan jaringan. */
    private fun publish(id: String, key: String, result: LyricsState, previous: Lyrics?): LyricsState {
        val shown: LyricsState = synchronized(lock) {
            val s = if (result is LyricsState.Failed && previous != null && !previous.isEmpty) {
                LyricsState.Ready(previous)
            } else {
                result
            }
            if (s is LyricsState.Ready) memory[id] = Entry(s.lyrics, key, clock())
            flows[id]?.value = s
            s
        }
        if (result is LyricsState.Ready) {
            if (result.lyrics.isEmpty) deleteDisk(id) else writeDisk(id, result.lyrics)
        }
        if (shown is LyricsState.Ready) maybeAutoSync(id, shown.lyrics)
        return shown
    }

    /**
     * Waktu lirik dibuat untuk versi lagu, sedangkan yang diputar video klip: cocokkan suara keduanya
     * di latar belakang, lalu geser setiap baris ke waktu yang pas di video. Sekali per video.
     */
    private fun maybeAutoSync(id: String, l: Lyrics) {
        val align = autoSync ?: return
        if (!l.canAutoSync || !autoSyncEnabled()) return
        val refId = l.timingRefId ?: return
        val lines = l.synced ?: return
        synchronized(lock) {
            if (id in syncing || (syncRetryAt[id] ?: 0) > clock()) return
            syncing += id
            flows[id]?.value = LyricsState.Ready(l, syncing = true)
        }
        scope.launch {
            val result = try {
                align(id, refId, lines.first().timeMs..lines.last().timeMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AutoSyncResult.Error(e)
            }
            val next: Lyrics? = when (result) {
                is AutoSyncResult.Aligned -> l.alignedWith(result.map) ?: l.copy(autoSync = Lyrics.AutoSync.NOT_MATCHING)
                AutoSyncResult.NotMatching -> l.copy(autoSync = Lyrics.AutoSync.NOT_MATCHING)
                is AutoSyncResult.Error -> null
            }
            val save = synchronized(lock) {
                syncing -= id
                val cur = memory[id]
                when {
                    // Selama proses, liriknya dicari ulang atau diselaraskan sendiri oleh pengguna: jangan ditimpa.
                    cur == null || cur.lyrics.synced != l.synced || cur.lyrics.userSynced -> {
                        cur?.let { flows[id]?.value = LyricsState.Ready(it.lyrics) }
                        null
                    }
                    next == null -> {
                        syncRetryAt[id] = clock() + SYNC_RETRY_MS
                        flows[id]?.value = LyricsState.Ready(cur.lyrics)
                        null
                    }
                    else -> {
                        memory[id] = Entry(next, cur.metaKey, cur.at)
                        flows[id]?.value = LyricsState.Ready(next)
                        next
                    }
                }
            }
            save?.let { writeDisk(id, it) }
        }
    }

    /** Buang aliran yang tidak diamati lagi supaya memori tidak menumpuk. */
    private fun trimFlows() {
        if (flows.size <= MAX_FLOWS) return
        val it = flows.entries.iterator()
        while (flows.size > MAX_FLOWS && it.hasNext()) {
            val e = it.next()
            if (e.value.subscriptionCount.value == 0 && !inflight.containsKey(e.key) && e.key !in syncing) it.remove()
        }
    }

    private fun metaKey(s: Song) = "${s.title}|${s.artist}|${s.durationSec}"

    // ------------------------------------------------------------------ simpanan offline

    private fun fileFor(id: String): File? =
        dir?.let { File(it, id.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } + ".json") }

    private fun readDisk(id: String): Lyrics? = runCatching {
        val f = fileFor(id)?.takeIf { it.exists() } ?: return null
        decode(JSONObject(f.readText())).takeIf { !it.isEmpty }
    }.getOrNull()

    private fun writeDisk(id: String, l: Lyrics) {
        runCatching {
            val f = fileFor(id) ?: return
            f.parentFile?.mkdirs()
            f.writeText(encode(l).toString())
            val files = f.parentFile?.listFiles()
            if (files != null && files.size > MAX_FILES) {
                files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES + 100).forEach { it.delete() }
            }
        }
    }

    private fun deleteDisk(id: String) {
        runCatching { fileFor(id)?.delete() }
    }

    companion object {
        private const val MEMORY_SIZE = 80
        private const val MAX_FLOWS = 24
        private const val MAX_FILES = 600
        private const val EMPTY_TTL_MS = 30 * 60_000L
        private const val SYNC_RETRY_MS = 2 * 60_000L
        private val OLD_DIRS = listOf("lyrics", "lyrics2")

        fun encode(l: Lyrics): JSONObject = JSONObject().apply {
            put("v", 3)
            l.synced?.let { lines ->
                put("s", JSONArray().also { a ->
                    lines.forEach { line ->
                        val row = JSONArray().put(line.timeMs).put(line.text)
                        line.words?.let { ws -> row.put(JSONArray().also { wa -> ws.forEach { wa.put(it.timeMs).put(it.start).put(it.end) } }) }
                        a.put(row)
                    }
                })
            }
            l.plain?.let { put("p", it) }
            put("src", l.source)
            if (l.offsetMs != 0L) put("o", l.offsetMs)
            put("tr", l.timingTrusted)
            if (l.userSynced) put("us", true)
            l.timingRefId?.let { put("ref", it) }
            if (l.autoSync != Lyrics.AutoSync.NONE) put("as", l.autoSync)
            if (l.approxTiming) put("ap", true)
        }

        fun decode(o: JSONObject): Lyrics {
            val arr = o.optJSONArray("s")
            val synced = arr?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONArray(i).let { row ->
                        val words = row.optJSONArray(2)?.let { wa ->
                            (0 until wa.length() / 3).map { k -> LyricWord(wa.getLong(k * 3), wa.getInt(k * 3 + 1), wa.getInt(k * 3 + 2)) }
                        }?.takeIf { it.isNotEmpty() }
                        LyricLine(row.getLong(0), row.getString(1), words)
                    }
                }
            }?.takeIf { it.isNotEmpty() }
            return Lyrics(
                synced = synced,
                plain = if (o.isNull("p")) null else o.optString("p").takeIf { it.isNotBlank() },
                source = o.optString("src"),
                offsetMs = o.optLong("o", 0),
                timingTrusted = o.optBoolean("tr", true),
                userSynced = o.optBoolean("us", false),
                timingRefId = if (o.isNull("ref")) null else o.optString("ref").takeIf { it.isNotBlank() },
                autoSync = o.optInt("as", Lyrics.AutoSync.NONE),
                approxTiming = o.optBoolean("ap", false),
            )
        }
    }
}
