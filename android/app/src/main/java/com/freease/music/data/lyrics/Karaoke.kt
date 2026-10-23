package com.freease.music.data.lyrics

import com.freease.music.data.model.LyricLine
import kotlin.math.max
import kotlin.math.min

/** Seberapa jauh baris lirik sudah "dinyanyikan", untuk efek karaoke per kata. */
object Karaoke {
    /** Perkiraan lama menyanyikan satu karakter kalau tidak ada waktu per kata. */
    private const val MS_PER_CHAR = 75L
    private const val MIN_LINE_MS = 1_200L

    /**
     * Jumlah karakter (boleh pecahan) baris [line] yang sudah dinyanyikan pada waktu lirik [t]
     * (posisi lagu dikurangi geseran sinkron). [nextLineMs] = waktu baris berikutnya, kalau ada.
     */
    fun sungChars(line: LyricLine, nextLineMs: Long?, t: Long): Float {
        val len = line.text.length
        if (len == 0 || t < line.timeMs) return 0f
        val words = line.words
        if (!words.isNullOrEmpty()) {
            val k = words.indexOfLast { it.timeMs <= t }
            if (k < 0) return 0f
            val w = words[k]
            val end = words.getOrNull(k + 1)?.timeMs
                ?: min(nextLineMs ?: Long.MAX_VALUE, w.timeMs + max(400L, (w.end - w.start) * MS_PER_CHAR * 2))
            val frac = if (end > w.timeMs) ((t - w.timeMs).toFloat() / (end - w.timeMs)).coerceIn(0f, 1f) else 1f
            // Spasi setelah kata ikut terisi bersama kata itu.
            val wordEnd = words.getOrNull(k + 1)?.start ?: max(w.end, if (k == words.lastIndex) len else w.end)
            return (w.start + frac * (wordEnd - w.start)).coerceIn(0f, len.toFloat())
        }
        val natural = max(MIN_LINE_MS, len * MS_PER_CHAR)
        val span = if (nextLineMs != null && nextLineMs > line.timeMs) min(nextLineMs - line.timeMs, natural) else natural
        return ((t - line.timeMs).toFloat() / span).coerceIn(0f, 1f) * len
    }
}
