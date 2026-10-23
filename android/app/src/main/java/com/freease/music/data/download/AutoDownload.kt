package com.freease.music.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.freease.music.FreeaseApp
import com.freease.music.playback.smart.Taste
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/** Bahan pertimbangan lagu mana yang layak diunduh otomatis. */
class DlCandidate(val id: String, val liked: Boolean, val playCount: Int, val lastPlayedAt: Long)

/** Lagu unduhan otomatis yang ditimbang untuk dibuang. */
class DlHeld(val id: String, val liked: Boolean, val lastPlayedAt: Long, val addedAt: Long)

/** Logika murni pemilihan dan pembersihan unduhan otomatis (tanpa Android). */
object AutoDownloadPlanner {
    const val LIMIT = 40
    const val PER_RUN = 8
    const val IDLE_DAYS = 45L

    fun score(c: DlCandidate, now: Long): Double {
        val recency = if (c.lastPlayedAt > 0) Taste.decay(now - c.lastPlayedAt, 30L * 24 * 3600_000) else 0.0
        return (if (c.liked) 3.0 else 0.0) + ln(1.0 + c.playCount) * 1.5 + recency * 2.0
    }

    /** Id lagu yang diunduh pada putaran ini: skor tertinggi dulu, tidak melebihi sisa kuota. */
    fun plan(candidates: List<DlCandidate>, heldCount: Int, now: Long, limit: Int = LIMIT, perRun: Int = PER_RUN): List<String> {
        val room = (limit - heldCount).coerceAtLeast(0)
        if (room == 0) return emptyList()
        return candidates.sortedByDescending { score(it, now) }.take(minOf(room, perRun)).map { it.id }
    }

    /** Unduhan otomatis yang tidak disukai dan sudah lama tidak diputar → dibuang untuk memberi ruang. */
    fun evict(held: List<DlHeld>, now: Long): List<String> {
        val limit = IDLE_DAYS * 24 * 3600_000
        return held.filter { h ->
            val last = maxOf(h.lastPlayedAt, h.addedAt)
            !h.liked && now - last > limit
        }.map { it.id }
    }
}

object AutoDownloader {
    private const val WORK = "auto_download"

    /** Jadwalkan atau batalkan pekerjaan berkala sesuai pengaturan. */
    fun sync(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<AutoDownloadWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private const val PREFS = "auto_download"
    private const val KEY = "ids"

    fun held(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun saveHeld(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, HashSet(ids)).apply()
    }
}

class AutoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c get() = (applicationContext as FreeaseApp).container

    override suspend fun doWork(): Result {
        if (!c.settings.current.autoDownload) return Result.success()
        val dao = c.db.songs()
        val now = System.currentTimeMillis()

        // Bersihkan dulu: yang sudah hilang dari disk keluar dari catatan, yang lama tak diputar dibuang.
        val done = dao.downloadedNow().associateBy { it.id }
        var held = AutoDownloader.held(applicationContext).filter { it in done }.toMutableSet()
        val stale = AutoDownloadPlanner.evict(
            held.mapNotNull { id -> done[id]?.let { DlHeld(id, it.liked, it.lastPlayedAt, it.addedAt) } }, now,
        )
        stale.forEach { c.downloads.remove(it); held.remove(it) }

        val candidates = dao.downloadCandidates().map { DlCandidate(it.id, it.liked, it.playCount, it.lastPlayedAt) }
        val ids = AutoDownloadPlanner.plan(candidates, held.size, now)
        if (ids.isNotEmpty()) {
            val byId = dao.getAll(ids).associateBy { it.id }
            c.downloads.download(ids.mapNotNull { byId[it]?.toSong() }, unmeteredOnly = true).join()
            held += ids
        }
        AutoDownloader.saveHeld(applicationContext, held)
        return Result.success()
    }
}
