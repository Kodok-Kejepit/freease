package com.freease.music.data.backup

import com.freease.music.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

class BackupPlaylist(val name: String, val description: String, val pinned: Boolean, val songs: List<Song>)
class BackupArtist(val url: String, val name: String, val thumbnail: String?)

/** Isi berkas cadangan: playlist lokal, lagu disukai, dan artis yang diikuti. */
class BackupData(
    val playlists: List<BackupPlaylist>,
    val liked: List<Song>,
    val artists: List<BackupArtist>,
)

class BackupSummary(val newLiked: Int, val playlists: Int, val songsAdded: Int, val artists: Int)

/** Format berkas cadangan JSON berversi; sengaja polos supaya mudah dibaca dan dipindah antar perangkat. */
object Backup {
    const val FORMAT = "freease-backup"
    const val VERSION = 1

    fun toJson(data: BackupData, exportedAt: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", exportedAt)
        root.put("liked", JSONArray().also { a -> data.liked.forEach { a.put(song(it)) } })
        root.put(
            "playlists",
            JSONArray().also { a ->
                data.playlists.forEach { p ->
                    a.put(
                        JSONObject().put("name", p.name).put("description", p.description).put("pinned", p.pinned)
                            .put("songs", JSONArray().also { sa -> p.songs.forEach { sa.put(song(it)) } }),
                    )
                }
            },
        )
        root.put(
            "artists",
            JSONArray().also { a ->
                data.artists.forEach { a.put(JSONObject().put("url", it.url).put("name", it.name).put("thumbnail", it.thumbnail ?: JSONObject.NULL)) }
            },
        )
        return root.toString()
    }

    /** @throws IllegalArgumentException kalau berkas bukan cadangan Freease atau versinya terlalu baru. */
    fun parse(json: String): BackupData {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("Berkas bukan cadangan Freease") }
        require(root.optString("format") == FORMAT) { "Berkas bukan cadangan Freease" }
        require(root.optInt("version", 0) in 1..VERSION) { "Versi cadangan tidak didukung" }
        val liked = root.optJSONArray("liked").songs()
        val playlists = root.optJSONArray("playlists").objects().map { p ->
            BackupPlaylist(
                name = p.optString("name").ifBlank { "Playlist" },
                description = p.optString("description"),
                pinned = p.optBoolean("pinned", false),
                songs = p.optJSONArray("songs").songs(),
            )
        }
        val artists = root.optJSONArray("artists").objects().mapNotNull { a ->
            val url = a.optString("url")
            if (url.isBlank()) null else BackupArtist(url, a.optString("name"), a.str("thumbnail"))
        }
        return BackupData(playlists, liked, artists)
    }

    private fun song(s: Song) = JSONObject()
        .put("id", s.id).put("title", s.title).put("artist", s.artist)
        .put("artistUrl", s.artistUrl ?: JSONObject.NULL).put("album", s.album ?: JSONObject.NULL)
        .put("durationSec", s.durationSec).put("thumbnail", s.thumbnail ?: JSONObject.NULL)

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.songs(): List<Song> = objects().mapNotNull { o ->
        val id = o.optString("id")
        if (id.isBlank()) null else Song(
            id = id, title = o.optString("title"), artist = o.optString("artist"),
            artistUrl = o.str("artistUrl"), album = o.str("album"),
            durationSec = o.optLong("durationSec", 0), thumbnail = o.str("thumbnail"),
        )
    }
}
