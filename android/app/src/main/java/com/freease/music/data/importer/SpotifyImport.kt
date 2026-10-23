package com.freease.music.data.importer

import com.freease.music.data.lyrics.LyricsMatch
import com.freease.music.data.model.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/** Satu lagu di playlist Spotify. */
data class SpotifyTrack(val title: String, val artists: String, val durationMs: Long)

data class SpotifyList(
    val type: String,
    val name: String,
    val owner: String?,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
    /** Spotify hanya memberi 100 lagu pertama lewat halaman publiknya. */
    val truncated: Boolean,
)

data class SpotifyRef(val type: String, val id: String) {
    val embedUrl: String get() = "https://open.spotify.com/embed/$type/$id"
}

/**
 * Membaca playlist / album / lagu Spotify publik tanpa login, dari halaman "embed" yang memang
 * disediakan Spotify untuk ditampilkan di situs lain.
 */
class SpotifyReader(private val client: OkHttpClient) {

    fun read(ref: SpotifyRef): SpotifyList {
        val req = Request.Builder().url(ref.embedUrl)
            .header("User-Agent", UA)
            .build()
        val html = client.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("Playlist tidak ditemukan atau bersifat pribadi")
            if (!r.isSuccessful) throw IOException("Spotify HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Balasan kosong")
        }
        return parse(html, ref.type)
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val EMBED_LIMIT = 100
        private val nextData = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        private val linkRe = Regex(
            "(?:open\\.spotify\\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]{10,40})",
            RegexOption.IGNORE_CASE,
        )

        fun isSpotifyLink(text: String): Boolean =
            linkRe.containsMatchIn(text) || text.contains("spotify.link/") || text.contains("spotify.app.link/")

        /** Link pendek spotify.link perlu diikuti dulu; selain itu langsung diurai. */
        fun parseLink(text: String): SpotifyRef? =
            linkRe.find(text)?.let { SpotifyRef(it.groupValues[1].lowercase(), it.groupValues[2]) }

        fun resolveShortLink(client: OkHttpClient, text: String): SpotifyRef? {
            parseLink(text)?.let { return it }
            val url = Regex("https?://\\S+").find(text)?.value ?: return null
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            return client.newCall(req).execute().use { r ->
                parseLink(r.request.url.toString()) ?: parseLink(r.body?.string()?.take(200_000) ?: "")
            }
        }

        /** Mengurai halaman embed (dipisah supaya bisa diuji dengan contoh halaman asli). */
        fun parse(html: String, type: String): SpotifyList {
            val json = nextData.find(html)?.groupValues?.get(1) ?: throw IOException("Format halaman Spotify berubah")
            val pageProps = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")
            if (pageProps?.optInt("status") == 404) throw IOException("Playlist tidak ditemukan, sudah dihapus, atau bersifat pribadi")
            val entity = pageProps?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                ?: throw IOException("Isi playlist tidak terbaca")
            val name = entity.optString("name").ifBlank { entity.optString("title") }
            val owner = entity.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
            val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url")
            }?.takeIf { it.isNotBlank() }
            val tracks = if (type == "track") {
                listOf(SpotifyTrack(name, clean(entity.optJSONArray("artists")?.let { a ->
                    (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() }
                } ?: owner.orEmpty()), entity.optLong("duration")))
            } else {
                val list = entity.optJSONArray("trackList")
                (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val t = list!!.optJSONObject(i) ?: return@mapNotNull null
                    val title = t.optString("title").trim()
                    if (title.isEmpty()) null else SpotifyTrack(title, clean(t.optString("subtitle")), t.optLong("duration"))
                }
            }
            return SpotifyList(type, name.ifBlank { "Playlist Spotify" }, owner, cover, tracks, type == "playlist" && tracks.size >= EMBED_LIMIT)
        }

        private fun clean(s: String) = s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }
}

/** Memilih video YouTube yang paling cocok untuk satu lagu Spotify. Murni, tanpa jaringan. */
object TrackMatcher {
    /** Toleransi beda durasi untuk dianggap rekaman yang sama (detik). */
    private const val SAME_SEC = 4
    private const val NEAR_SEC = 15

    fun best(track: SpotifyTrack, candidates: List<Song>): Song? {
        val firstArtist = track.artists.split(",", "&", " feat").first().trim()
        val dur = track.durationMs / 1000
        val scored = candidates.take(10).mapIndexedNotNull { rank, s ->
            if (s.isLive) return@mapIndexedNotNull null
            val titleOk = LyricsMatch.songMatches(track.title, firstArtist, s.title, s.artist) ||
                LyricsMatch.songMatches(track.title, firstArtist, s.title + " " + s.artist, firstArtist)
            if (!titleOk) return@mapIndexedNotNull null
            val diff = if (dur > 0 && s.durationSec > 0) abs(s.durationSec - dur) else 30
            var score = 100.0 - rank * 4
            score += when {
                diff <= SAME_SEC -> 40.0
                diff <= NEAR_SEC -> 20.0
                diff <= 60 -> 0.0
                else -> -60.0 // kompilasi / versi panjang
            }
            if (s.artist.contains("Topic") || LyricsMatch.artistMatches(firstArtist, s.artist)) score += 15
            // Banyak kata tambahan di judul video (terjemahan, "lirik", nama kanal penggemar) = bukan unggahan resmi.
            score -= 25 * (1 - LyricsMatch.coverage(s.title, "${track.title} ${track.artists}"))
            s to score
        }
        return scored.maxByOrNull { it.second }?.takeIf { it.second > 40 }?.first
    }
}
