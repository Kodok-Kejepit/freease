package com.freease.music.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.freease.music.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Perekam masalah di perangkat sendiri (tanpa server):
 * - crash (exception yang tidak tertangkap) → stack trace disimpan ke file
 * - main thread macet > 10 detik → stack main thread disimpan (cikal bakal ANR)
 * - Android 11+: alasan app berhenti dari sistem (ANR, crash native, kehabisan memori)
 * Laporan ditawarkan untuk dibagikan saat app dibuka berikutnya.
 */
object CrashReporter {
    private const val PREFS = "crash_reporter"
    private const val KEY_ACK = "acknowledged_at"
    private const val MAX_FILES = 5

    private lateinit var dir: File

    fun install(context: Context) {
        dir = File(context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write("crash", buildString {
                    appendLine("Jenis : Crash (exception)")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(stackTrace(error))
                })
            }
            previous?.uncaughtException(thread, error)
        }
        // Di Robolectric main looper sengaja ditahan oleh tes → watchdog akan salah lapor.
        if (Build.FINGERPRINT != "robolectric") startWatchdog()
    }

    /** Thread kecil yang memeriksa apakah main thread masih merespons (tiap 10 detik). */
    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reportedForCurrentFreeze = false
            while (true) {
                val answered = AtomicBoolean(false)
                main.post { answered.set(true) }
                Thread.sleep(10_000)
                if (!answered.get() && !reportedForCurrentFreeze) {
                    reportedForCurrentFreeze = true
                    runCatching {
                        val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                        write("freeze", "Jenis : Main thread macet > 10 detik\n\n$trace\n")
                    }
                } else if (answered.get()) {
                    reportedForCurrentFreeze = false
                }
            }
        }, "freease-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun header(): String = buildString {
        appendLine("Freease ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val rt = Runtime.getRuntime()
        appendLine("Memori: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB dipakai dari ${rt.maxMemory() / 1_048_576} MB")
        appendLine("Waktu: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
    }

    private fun write(kind: String, body: String) {
        if (!::dir.isInitialized) return
        File(dir, "$kind-${System.currentTimeMillis()}.txt").writeText(header() + "\n" + body)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Laporan yang belum pernah ditampilkan ke pengguna, atau null kalau tidak ada. */
    fun pendingReport(context: Context): String? {
        val ack = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ACK, 0)
        return collect(context, since = ack)
    }

    /** Laporan terbaru (untuk menu Pengaturan), termasuk yang sudah pernah ditampilkan. */
    fun latestReport(context: Context): String? = collect(context, since = 0)

    fun acknowledge(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ACK, System.currentTimeMillis()).apply()
    }

    private fun collect(context: Context, since: Long): String? {
        val parts = mutableListOf<Pair<Long, String>>()
        if (::dir.isInitialized) {
            dir.listFiles()?.filter { it.lastModified() > since }?.forEach { parts += it.lastModified() to it.readText() }
        }
        if (Build.VERSION.SDK_INT >= 30) parts += exitReasons(context, since)
        if (parts.isEmpty()) return null
        return parts.sortedByDescending { it.first }.take(3).joinToString("\n\n──────────\n\n") { it.second }
    }

    private fun exitReasons(context: Context, since: Long): List<Pair<Long, String>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull() ?: return emptyList()
        return infos.filter { it.timestamp > since }.mapNotNull { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR (aplikasi tidak menanggapi)"
                ApplicationExitInfo.REASON_CRASH -> "Crash (Java/Kotlin)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crash native"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Dihentikan sistem: memori HP penuh"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Dihentikan sistem: pemakaian CPU/baterai berlebihan"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Gagal memulai"
                else -> return@mapNotNull null
            }
            val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { info.traceInputStream?.bufferedReader()?.use { mainThreadSection(it.readText()) } }.getOrNull()
            } else null
            info.timestamp to buildString {
                appendLine("Freease ${BuildConfig.VERSION_NAME} • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}")
                appendLine("Waktu: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))}")
                appendLine("Jenis : $kind (laporan sistem)")
                appendLine("Proses: ${info.processName} • PSS ${info.pss / 1024} MB • RSS ${info.rss / 1024} MB")
                info.description?.let { appendLine("Detail: $it") }
                if (trace != null) {
                    appendLine()
                    append(trace)
                }
            }
        }
    }

    /** Ambil bagian thread "main" dari trace ANR (trace lengkap bisa ratusan KB). */
    private fun mainThreadSection(full: String): String {
        val start = full.indexOf("\"main\"")
        if (start < 0) return full.take(4000)
        val end = full.indexOf("\n\n", start).takeIf { it > 0 } ?: (start + 6000)
        return full.substring(start, minOf(end, start + 6000, full.length))
    }
}
