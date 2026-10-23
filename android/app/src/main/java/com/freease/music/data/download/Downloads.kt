package com.freease.music.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.freease.music.FreeaseApp
import com.freease.music.R
import com.freease.music.data.db.DownloadState
import com.freease.music.data.model.Song
import com.freease.music.data.prefs.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Antrian unduhan (offline) — dijalankan WorkManager supaya tetap jalan saat app ditutup. */
class DownloadManager(private val context: Context, private val scope: CoroutineScope) {
    private val app get() = context.applicationContext as FreeaseApp
    private val wm get() = WorkManager.getInstance(context)

    fun download(songs: List<Song>, unmeteredOnly: Boolean = false) = scope.launch {
        val c = app.container
        songs.filter { !it.isLive }.forEach { song ->
            c.library.save(song)
            val existing = c.db.songs().get(song.id)
            if (existing?.downloadState == DownloadState.DONE && existing.downloadPath?.let { File(it).exists() } == true) return@forEach
            c.db.songs().setDownloadState(song.id, DownloadState.QUEUED, 0f)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (unmeteredOnly || c.settings.current.downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf("id" to song.id))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork("dl_${song.id}", ExistingWorkPolicy.KEEP, req)
        }
    }

    fun remove(songId: String) = scope.launch {
        wm.cancelUniqueWork("dl_$songId")
        val dao = app.container.db.songs()
        dao.get(songId)?.downloadPath?.let { File(it).delete() }
        dao.setDownloaded(songId, DownloadState.NONE, null, 0)
    }

    fun dir(): File = File(context.filesDir, "offline").apply { mkdirs() }

    companion object {
        const val TAG = "freease_download"
        const val CHANNEL = "downloads"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Unduhan", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progres unduhan lagu untuk diputar offline"
                    },
                )
            }
        }
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c get() = (applicationContext as FreeaseApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Menyiapkan unduhan…", 0)

    private fun foreground(title: String, progress: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, DownloadManager.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Freease · mengunduh untuk offline")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val nid = 0x4600 + ((inputData.getString("id")?.hashCode() ?: 0) and 0xff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(nid, n)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val dao = c.db.songs()
        val meta = dao.get(id)
        runCatching { setForeground(foreground(meta?.title ?: "Mengunduh", 0)) }
        try {
            dao.setDownloadState(id, DownloadState.RUNNING, 0f)
            val info = c.youtube.streamInfo(id)
            val stream = c.youtube.pickAudio(info, AudioQuality.MAX) ?: error("Tidak ada stream audio")
            val ext = if (stream.mimeType?.contains("webm") == true) "webm" else "m4a"
            val dest = File(c.downloads.dir(), "$id.$ext")
            val tmp = File(dest.path + ".part")
            val total = fetch(stream.url, tmp) { p ->
                dao.setDownloadState(id, DownloadState.RUNNING, p)
                runCatching { setForeground(foreground(meta?.title ?: "Mengunduh", (p * 100).toInt())) }
            }
            if (!tmp.renameTo(dest)) error("Gagal menyimpan berkas")
            dao.setDownloaded(id, DownloadState.DONE, dest.absolutePath, total)
            // Lirik ikut disimpan supaya lagu offline tetap berlirik.
            meta?.let { m -> runCatching { c.lyrics.get(m.toSong()) } }
            Result.success()
        } catch (e: Exception) {
            c.youtube.invalidate(id)
            if (runAttemptCount < 2) {
                dao.setDownloadState(id, DownloadState.QUEUED, 0f)
                Result.retry()
            } else {
                dao.setDownloadState(id, DownloadState.FAILED, 0f)
                Result.failure()
            }
        }
    }

    /** Unduh per potongan 4 MB — YouTube memperlambat permintaan rentang besar. */
    private suspend fun fetch(url: String, file: File, onProgress: suspend (Float) -> Unit): Long {
        val chunk = 4L * 1024 * 1024
        var offset = if (file.exists()) file.length() else 0L
        var total = -1L
        var lastReport = 0f
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            while (total < 0 || offset < total) {
                currentCoroutineContext().ensureActive()
                val end = if (total > 0) minOf(offset + chunk, total) - 1 else offset + chunk - 1
                val req = Request.Builder().url(url).header("Range", "bytes=$offset-$end").build()
                c.streamClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    if (total < 0) {
                        total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: resp.body?.contentLength()?.takeIf { it > 0 && resp.code == 200 }
                            ?: error("Ukuran berkas tidak diketahui")
                    }
                    val buf = ByteArray(64 * 1024)
                    resp.body!!.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            offset += n
                        }
                    }
                    if (resp.code == 200) offset = total
                }
                val p = offset.toFloat() / total
                if (p - lastReport >= 0.04f || offset >= total) {
                    lastReport = p
                    onProgress(p)
                }
            }
        }
        return total
    }
}
