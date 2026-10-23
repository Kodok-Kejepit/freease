package com.freease.music.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.ForwardingSource
import okio.Buffer
import okio.buffer
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * Penghitung pemakaian data (unduh) per bulan, dipisah Wi-Fi dan seluler. Hanya menjumlah byte
 * yang benar-benar lewat jaringan (cache tidak terhitung) dan menulis ke disk paling cepat tiap 20 detik.
 */
class DataUsage(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("data_usage", Context.MODE_PRIVATE)
    private val wifi = AtomicLong()
    private val cell = AtomicLong()
    @Volatile private var meteredAt = 0L
    @Volatile private var meteredNow = false
    private var flushJob: Job? = null

    private fun isMetered(): Boolean {
        val t = System.currentTimeMillis()
        if (t - meteredAt > 5_000) {
            meteredNow = runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }.getOrDefault(false)
            meteredAt = t
        }
        return meteredNow
    }

    fun add(bytes: Long) {
        if (bytes <= 0) return
        if (isMetered()) cell.addAndGet(bytes) else wifi.addAndGet(bytes)
        if (flushJob?.isActive != true) {
            flushJob = scope.launch {
                delay(20_000)
                flush()
            }
        }
    }

    @Synchronized
    fun flush() {
        val w = wifi.getAndSet(0)
        val c = cell.getAndSet(0)
        if (w == 0L && c == 0L) return
        val key = monthKey(System.currentTimeMillis())
        prefs.edit()
            .putLong("${key}_wifi", prefs.getLong("${key}_wifi", 0) + w)
            .putLong("${key}_cell", prefs.getLong("${key}_cell", 0) + c)
            .apply()
    }

    /** (Wi-Fi, seluler) bulan ini dalam byte. */
    fun thisMonth(): Pair<Long, Long> {
        val key = monthKey(System.currentTimeMillis())
        return (prefs.getLong("${key}_wifi", 0) + wifi.get()) to (prefs.getLong("${key}_cell", 0) + cell.get())
    }

    @Synchronized
    fun reset() {
        wifi.set(0)
        cell.set(0)
        prefs.edit().clear().apply()
    }

    val interceptor = Interceptor { chain ->
        val res = chain.proceed(chain.request())
        val body = res.body ?: return@Interceptor res
        val counting = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) add(n)
                return n
            }
        }
        res.newBuilder().body(counting.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }

    companion object {
        fun monthKey(ts: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            return "%04d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }
    }
}
