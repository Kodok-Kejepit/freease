package com.freease.music.data.lyrics

import com.freease.music.data.model.LyricLine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Satu lagu (versi audio resmi) hasil pencarian YouTube Music. */
class MusicTrack(val videoId: String, val title: String, val artists: String, val durationSec: Long)

/** Lirik dari YouTube Music. [timed] = setiap baris punya waktu mulai yang pas untuk video tersebut. */
class MusicLyrics(val lines: List<LyricLine>, val timed: Boolean, val source: String?)

/** Jenis video di YouTube Music + id halaman liriknya. */
class WatchInfo(val videoType: String?, val lyricsId: String?) {
    /** Versi audio resmi ("Topic"): waktu lirik apa pun yang dibuat untuk lagu ini pas dengannya. */
    val isAudioTrack: Boolean get() = videoType == "MUSIC_VIDEO_TYPE_ATV"
}

/** Sumber lirik dari YouTube Music. Antarmuka supaya bisa diganti tiruan saat pengujian. */
interface MusicLyricsSource {
    fun watchInfo(videoId: String): WatchInfo
    fun lyrics(lyricsId: String): MusicLyrics?
    fun searchSongs(query: String): List<MusicTrack>
}

/**
 * Lirik resmi YouTube Music (dari LyricFind), sama dengan yang tampil di aplikasi YouTube Music.
 * Setiap permintaan memakai X-Goog-FieldMask sehingga balasannya cuma ±1–9 KB, bukan ratusan KB.
 * Melempar [IOException] kalau jaringan/server gagal; mengembalikan null/kosong kalau memang tidak ada.
 */
class YouTubeMusicLyrics(
    private val client: OkHttpClient,
    private val region: () -> String = { "ID" },
) : MusicLyricsSource {

    override fun watchInfo(videoId: String): WatchInfo =
        YtmParse.watchInfo(post("next", JSONObject().put("videoId", videoId), android = true, mask = NEXT_MASK), videoId)

    override fun lyrics(lyricsId: String): MusicLyrics? {
        val o = post("browse", JSONObject().put("browseId", lyricsId), android = true, mask = LYRICS_MASK)
        if (YtmParse.isLyricsMessage(o)) return null
        YtmParse.lyrics(o)?.let { return it }
        // Format balasan klien Android tidak dikenali: pakai halaman web (teks saja, tanpa waktu).
        return YtmParse.webLyrics(post("browse", JSONObject().put("browseId", lyricsId), android = false, mask = WEB_LYRICS_MASK))
    }

    override fun searchSongs(query: String): List<MusicTrack> =
        YtmParse.songs(post("search", JSONObject().put("query", query).put("params", SONGS_PARAMS), android = false, mask = SEARCH_MASK))

    private fun post(endpoint: String, body: JSONObject, android: Boolean, mask: String): JSONObject {
        val c = JSONObject()
            .put("clientName", if (android) "ANDROID_MUSIC" else "WEB_REMIX")
            .put("clientVersion", if (android) ANDROID_VERSION else WEB_VERSION)
            .put("hl", "id")
            .put("gl", region().ifBlank { "ID" })
        if (android) c.put("androidSdkVersion", 34).put("osName", "Android").put("osVersion", "14")
        body.put("context", JSONObject().put("client", c))
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", if (android) ANDROID_UA else WEB_UA)
            .header("X-Goog-FieldMask", mask)
            .apply { if (!android) header("Origin", ORIGIN).header("Referer", "$ORIGIN/") }
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "YouTube Music HTTP ${r.code}")
            val text = r.body?.string() ?: throw IOException("YouTube Music: balasan kosong")
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("YouTube Music: balasan rusak") }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val ORIGIN = "https://music.youtube.com"
        const val ANDROID_VERSION = "7.27.52"
        const val ANDROID_UA = "com.google.android.apps.youtube.music/$ANDROID_VERSION (Linux; U; Android 14) gzip"
        const val WEB_VERSION = "1.20250310.01.00"
        const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        /** Filter pencarian "Lagu" YouTube Music (hanya versi audio resmi). */
        const val SONGS_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        const val TABS = "contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.tabRenderer"
        const val NEXT_MASK = "$TABS.endpoint.browseEndpoint.browseId," +
            "$TABS.content.musicQueueRenderer.content.playlistPanelRenderer.contents.playlistPanelVideoRenderer(videoId," +
            "navigationEndpoint.watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType)"
        const val LYRICS_MASK = "contents.elementRenderer.newElement.type.componentType.model(" +
            "timedLyricsModel.lyricsData(timedLyricsData,sourceMessage),musicMessageModel.text)"
        const val WEB_LYRICS_MASK = "contents.sectionListRenderer.contents.musicDescriptionShelfRenderer(description,footer)," +
            "contents.messageRenderer.text"
        const val SHELF = "contents.tabbedSearchResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents" +
            ".musicShelfRenderer.contents.musicResponsiveListItemRenderer"
        const val SEARCH_MASK = "$SHELF.flexColumns.musicResponsiveListItemFlexColumnRenderer.text.runs.text," +
            "$SHELF.playlistItemData.videoId"
    }
}

/** Pengurai balasan InnerTube, terpisah supaya bisa diuji dengan contoh balasan asli. */
object YtmParse {
    private fun JSONObject.path(vararg keys: String): JSONObject? {
        var cur: JSONObject? = this
        for (k in keys) cur = cur?.optJSONObject(k) ?: return null
        return cur
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    fun watchInfo(o: JSONObject, videoId: String): WatchInfo {
        val tabs = o.path("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")?.objects().orEmpty()
        var type: String? = null
        var lyricsId: String? = null
        for (t in tabs) {
            val tab = t.optJSONObject("tabRenderer") ?: continue
            tab.path("endpoint", "browseEndpoint")?.optString("browseId")
                ?.takeIf { it.startsWith("MPLY") }?.let { lyricsId = it }
            val queue = tab.path("content", "musicQueueRenderer", "content", "playlistPanelRenderer")
                ?.optJSONArray("contents")?.objects().orEmpty()
            for (item in queue) {
                val v = item.optJSONObject("playlistPanelVideoRenderer") ?: continue
                if (v.optString("videoId") != videoId) continue
                type = v.path("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")
                    ?.optString("musicVideoType")?.takeIf { it.isNotEmpty() }
                if (type != null) break
            }
        }
        return WatchInfo(type, lyricsId)
    }

    /** Balasan "Lirik tidak tersedia". */
    fun isLyricsMessage(o: JSONObject): Boolean =
        o.path("contents", "elementRenderer", "newElement", "type", "componentType", "model", "musicMessageModel") != null

    fun lyrics(o: JSONObject): MusicLyrics? {
        val data = o.path("contents", "elementRenderer", "newElement", "type", "componentType", "model", "timedLyricsModel", "lyricsData")
            ?: return null
        val items = data.optJSONArray("timedLyricsData")?.objects().orEmpty()
        if (items.isEmpty()) return null
        var timed = true
        val lines = items.map { x ->
            val start = x.optJSONObject("cueRange")?.optString("startTimeMilliseconds")?.toLongOrNull()
            if (start == null) timed = false
            LyricLine(start ?: 0L, x.optString("lyricLine").trim())
        }
        val source = data.optString("sourceMessage").trim().takeIf { it.isNotEmpty() }
        return MusicLyrics(if (timed) lines.sortedBy { it.timeMs } else lines, timed, source)
    }

    fun webLyrics(o: JSONObject): MusicLyrics? {
        val shelf = o.path("contents", "sectionListRenderer")?.optJSONArray("contents")?.objects()
            ?.firstNotNullOfOrNull { it.optJSONObject("musicDescriptionShelfRenderer") } ?: return null
        val text = runs(shelf.optJSONObject("description")).trim()
        if (text.isEmpty()) return null
        val source = runs(shelf.optJSONObject("footer")).trim().takeIf { it.isNotEmpty() }
        return MusicLyrics(text.lines().map { LyricLine(0L, it.trim()) }, timed = false, source = source)
    }

    private fun runs(o: JSONObject?): String =
        o?.optJSONArray("runs")?.objects()?.joinToString("") { it.optString("text") } ?: ""

    fun songs(o: JSONObject): List<MusicTrack> {
        val shelves = o.path("contents", "tabbedSearchResultsRenderer")?.optJSONArray("tabs")?.objects().orEmpty()
            .flatMap { tab ->
                tab.path("tabRenderer", "content", "sectionListRenderer")?.optJSONArray("contents")?.objects().orEmpty()
            }
            .mapNotNull { it.optJSONObject("musicShelfRenderer") }
        return shelves.flatMap { it.optJSONArray("contents")?.objects().orEmpty() }.mapNotNull { item ->
            val r = item.optJSONObject("musicResponsiveListItemRenderer") ?: return@mapNotNull null
            val id = r.path("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cols = r.optJSONArray("flexColumns")?.objects().orEmpty()
                .map { c -> c.path("musicResponsiveListItemFlexColumnRenderer", "text")?.optJSONArray("runs")?.objects().orEmpty().map { it.optString("text") } }
            val title = cols.getOrNull(0)?.joinToString("")?.trim().orEmpty()
            val meta = cols.getOrNull(1).orEmpty()
            // Kolom kedua: "Artis[ & Artis]" • "Album" • "4.07"
            val parts = meta.joinToString("").split(" • ", "•").map { it.trim() }.filter { it.isNotEmpty() }
            val artists = parts.firstOrNull().orEmpty()
            val duration = parts.lastOrNull()?.let(::parseDuration) ?: 0L
            if (title.isEmpty()) null else MusicTrack(id, title, artists, duration)
        }
    }

    /** "4.07", "4:07", "1.02.03" → detik; null kalau bukan durasi. */
    fun parseDuration(s: String): Long? {
        val parts = s.trim().split(':', '.')
        if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        if (parts.drop(1).any { it.length != 2 }) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    }
}
