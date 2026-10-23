package com.freease.music.playback

import android.content.Context
import com.freease.music.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedQueue(val songs: List<Song>, val index: Int, val positionMs: Long)

/** Menyimpan antrian terakhir supaya bisa dilanjutkan setelah app/HP dimulai ulang. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")

    fun save(q: SavedQueue) {
        runCatching {
            val arr = JSONArray()
            q.songs.take(1000).forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id).put("title", s.title).put("artist", s.artist)
                        .put("artistUrl", s.artistUrl).put("album", s.album)
                        .put("duration", s.durationSec).put("thumb", s.thumbnail).put("live", s.isLive).put("why", s.smartWhy),
                )
            }
            val o = JSONObject().put("songs", arr).put("index", q.index).put("position", q.positionMs)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    fun load(): SavedQueue? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("songs")
        val songs = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Song(
                id = s.getString("id"),
                title = s.optString("title"),
                artist = s.optString("artist"),
                artistUrl = s.optString("artistUrl").takeIf { it.isNotBlank() && it != "null" },
                album = s.optString("album").takeIf { it.isNotBlank() && it != "null" },
                durationSec = s.optLong("duration"),
                thumbnail = s.optString("thumb").takeIf { it.isNotBlank() && it != "null" },
                isLive = s.optBoolean("live"),
                smartWhy = s.optString("why").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        if (songs.isEmpty()) null
        else SavedQueue(songs, o.optInt("index").coerceIn(0, songs.lastIndex), o.optLong("position"))
    }.getOrNull()
}
