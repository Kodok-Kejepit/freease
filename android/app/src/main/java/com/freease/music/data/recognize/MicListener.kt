package com.freease.music.data.recognize

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Mendengarkan lewat mikrofon dan mencoba mengenali lagu bertahap (setelah ±4, 7, dan 11 detik)
 * sambil terus merekam — biasanya sudah ketemu di percobaan pertama.
 */
class MicListener(private val recognizer: SongRecognizer) {
    sealed interface Outcome {
        data class Found(val song: RecognizedSong) : Outcome
        data object NotFound : Outcome
        data class Failed(val message: String) : Outcome
    }

    @SuppressLint("MissingPermission")
    suspend fun listen(onLevel: (Float) -> Unit, onProgress: (Float) -> Unit): Outcome = withContext(Dispatchers.Default) {
        val rate = SignatureGenerator.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext Outcome.Failed("Mikrofon tidak tersedia")
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return@withContext Outcome.Failed("Mikrofon sedang dipakai aplikasi lain")
        }
        val gen = SignatureGenerator()
        val checkpoints = ArrayDeque(listOf(4 * rate, 7 * rate, 11 * rate))
        val total = 12 * rate
        val buf = ShortArray(1024) // kelipatan 128
        var pending: kotlinx.coroutines.Deferred<Result<RecognizedSong?>>? = null
        var lastError: Throwable? = null
        try {
            withContext(Dispatchers.IO) { record.startRecording() }
            while (gen.numberSamples < total) {
                ensureActive()
                val n = withContext(Dispatchers.IO) { record.read(buf, 0, buf.size) }
                if (n <= 0) return@withContext Outcome.Failed("Gagal membaca mikrofon")
                gen.feed(buf, n - n % 128)
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                onLevel((sqrt(sum / n) / 6000).toFloat().coerceIn(0f, 1f))
                onProgress(gen.numberSamples.toFloat() / total)

                pending?.takeIf { it.isCompleted }?.let { d ->
                    pending = null
                    d.await().onSuccess { if (it != null) return@withContext Outcome.Found(it) }.onFailure { lastError = it }
                }
                if (pending == null && checkpoints.isNotEmpty() && gen.numberSamples >= checkpoints.first()) {
                    checkpoints.removeFirst()
                    val snapshot = gen.snapshot()
                    pending = async(Dispatchers.IO) { runCatching { recognizer.recognize(snapshot) } }
                }
            }
            withContext(Dispatchers.IO) { record.stop() }
            // Percobaan terakhir dengan seluruh rekaman.
            pending?.await()?.onSuccess { if (it != null) return@withContext Outcome.Found(it) }?.onFailure { lastError = it }
            val final = runCatching { recognizer.recognize(gen) }
            final.onSuccess { if (it != null) return@withContext Outcome.Found(it) }.onFailure { lastError = it }
            if (final.isFailure && lastError != null) Outcome.Failed("Tidak bisa terhubung. Periksa internet lalu coba lagi.") else Outcome.NotFound
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
