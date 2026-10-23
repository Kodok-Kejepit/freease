package com.freease.music.data.lyrics

import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.LyricWord
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Satu entri LRCLIB. [durationSec] diisi pengunggah, sering asal-asalan — jangan dipercaya sendirian. */
class LrcEntry(
    val id: Long,
    val title: String,
    val artist: String,
    val durationSec: Double,
    val synced: List<LyricLine>?,
    val plain: String?,
)

interface LrcSource {
    fun search(title: String, artist: String): List<LrcEntry>
    fun searchQuery(q: String): List<LrcEntry>
}

/** Galat HTTP dari server (bukan putus jaringan). 4xx berarti sumbernya menolak, bukan koneksi buruk. */
class HttpStatusException(val code: Int, msg: String) : IOException(msg) {
    val isClientError: Boolean get() = code in 400..499 && code != 408 && code != 429
}

/** LRCLIB: lirik komunitas gratis tanpa API key. */
class LrcLib(private val client: OkHttpClient) : LrcSource {
    override fun search(title: String, artist: String): List<LrcEntry> = entries(
        "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build(),
    )

    override fun searchQuery(q: String): List<LrcEntry> =
        entries("https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", q).build())

    private fun entries(url: HttpUrl): List<LrcEntry> {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Freease/1.1 (https://github.com/Kodok-Kejepit/freease)")
            .build()
        var attempt = 0
        val body: String
        while (true) {
            val result = client.newCall(req).execute().use { r ->
                when {
                    r.code == 404 -> return emptyList()
                    r.isSuccessful -> r.body?.string() ?: throw IOException("LRCLIB: balasan kosong")
                    // Server sibuk (503/429/5xx) sering pulih dalam hitungan detik: coba sekali lagi.
                    (r.code == 429 || r.code >= 500) && attempt == 0 -> null
                    else -> throw HttpStatusException(r.code, "LRCLIB HTTP ${r.code}")
                }
            }
            if (result != null) {
                body = result
                break
            }
            attempt++
            Thread.sleep(RETRY_DELAY_MS)
        }
        val arr = runCatching { JSONArray(body) }.getOrElse { throw IOException("LRCLIB: balasan rusak") }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entry) }
    }

    companion object {
        private const val RETRY_DELAY_MS = 900L
        private val lineRe = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)$")

        fun entry(o: JSONObject): LrcEntry? {
            val synced = o.str("syncedLyrics")?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
            val plain = o.str("plainLyrics") ?: synced?.joinToString("\n") { it.text }
            if (synced == null && plain.isNullOrBlank()) return null
            return LrcEntry(
                id = o.optLong("id"),
                title = o.str("trackName") ?: o.str("name") ?: "",
                artist = o.str("artistName") ?: "",
                durationSec = o.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                synced = synced,
                plain = plain,
            )
        }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private val wordRe = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

        private fun ms(mm: String, ss: String, frac: String): Long = mm.toLong() * 60_000 + ss.toLong() * 1000 + when (frac.length) {
            0 -> 0
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.take(3).toInt()
        }

        /**
         * Baris LRC "[mm:ss.xx] teks"; baris tanpa waktu (metadata) diabaikan. Format "enhanced"
         * "[00:12.00] <00:12.00> kata <00:12.40> kata" menghasilkan waktu per kata.
         */
        fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence().mapNotNull { raw ->
            val m = lineRe.find(raw.trim()) ?: return@mapNotNull null
            val (mm, ss, frac, body) = m.destructured
            val lineMs = ms(mm, ss, frac)
            if (!wordRe.containsMatchIn(body)) return@mapNotNull LyricLine(lineMs, body.trim())
            val text = StringBuilder()
            val words = ArrayList<LyricWord>()
            var pos = 0
            var pendingMs: Long? = null
            for (tag in wordRe.findAll(body)) {
                appendWords(body.substring(pos, tag.range.first), pendingMs, text, words)
                pendingMs = ms(tag.groupValues[1], tag.groupValues[2], tag.groupValues[3])
                pos = tag.range.last + 1
            }
            appendWords(body.substring(pos), pendingMs, text, words)
            val clean = text.toString()
            val trimmed = clean.trimStart()
            val cut = clean.length - trimmed.length
            LyricLine(
                lineMs, trimmed.trimEnd(),
                words.map { it.copy(start = (it.start - cut).coerceAtLeast(0), end = (it.end - cut).coerceAtLeast(0)) }
                    .filter { it.end > it.start }.takeIf { it.isNotEmpty() },
            )
        }.sortedBy { it.timeMs }.toList()

        private fun appendWords(raw: String, timeMs: Long?, text: StringBuilder, words: MutableList<LyricWord>) {
            // Spasi di kedua sisi tag waktu tidak boleh jadi spasi ganda.
            val chunk = if (text.isNotEmpty() && text.last().isWhitespace()) raw.trimStart() else raw
            if (chunk.isEmpty()) return
            val start = text.length
            text.append(chunk)
            val core = chunk.trimEnd()
            if (timeMs != null && core.isNotBlank()) {
                val lead = core.length - core.trimStart().length
                words += LyricWord(timeMs, start + lead, start + core.length)
            }
        }
    }
}
