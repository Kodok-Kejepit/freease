package com.freease.music.data.lyrics

import android.util.LruCache
import com.freease.music.data.model.LyricLine
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.data.model.TitleCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Lirik tersinkron dari LRCLIB (gratis, tanpa API key). */
class LyricsRepository(private val client: OkHttpClient) {
    private val cache = LruCache<String, Lyrics>(40)
    private val lineRe = Regex("^\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)$")

    suspend fun get(song: Song): Lyrics = withContext(Dispatchers.IO) {
        cache.get(song.id)?.let { return@withContext it }
        val title = TitleCleaner.cleanTitle(song.title)
        val artist = TitleCleaner.cleanArtist(song.artist)
        val result = exact(title, artist, song.durationSec)
            ?: search(title, artist, song.durationSec)
            ?: searchQ("$artist $title", song.durationSec)
            ?: Lyrics(null, null)
        cache.put(song.id, result)
        result
    }

    private fun call(url: okhttp3.HttpUrl): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Freease/1.0 (https://github.com/Kodok-Kejepit/freease)")
            .build()
        return runCatching {
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    private fun exact(title: String, artist: String, duration: Long): Lyrics? {
        val b = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
        if (duration > 0) b.addQueryParameter("duration", duration.toString())
        val body = call(b.build()) ?: return null
        return runCatching { parse(JSONObject(body)) }.getOrNull()?.takeIf { !it.isEmpty }
    }

    private fun search(title: String, artist: String, duration: Long): Lyrics? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        return pickBest(call(url), duration)
    }

    private fun searchQ(q: String, duration: Long): Lyrics? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", q).build()
        return pickBest(call(url), duration)
    }

    private fun pickBest(body: String?, duration: Long): Lyrics? {
        body ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        val sorted = list.sortedWith(
            compareByDescending<JSONObject> { !it.optString("syncedLyrics").isNullOrBlank() && it.optString("syncedLyrics") != "null" }
                .thenBy { if (duration > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - duration) else 0.0 },
        )
        val best = sorted.firstOrNull {
            duration <= 0 || kotlin.math.abs(it.optDouble("duration", duration.toDouble()) - duration) < 15
        } ?: return null
        return parse(best).takeIf { !it.isEmpty }
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun parse(o: JSONObject): Lyrics {
        val synced = o.str("syncedLyrics")?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        val plain = o.str("plainLyrics")
        return Lyrics(synced, plain ?: synced?.joinToString("\n") { it.text })
    }

    fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence().mapNotNull { raw ->
        val m = lineRe.find(raw.trim()) ?: return@mapNotNull null
        val (mm, ss, frac, text) = m.destructured
        val ms = when (frac.length) {
            0 -> 0
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.take(3).toInt()
        }
        LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, text.trim())
    }.sortedBy { it.timeMs }.toList()
}
