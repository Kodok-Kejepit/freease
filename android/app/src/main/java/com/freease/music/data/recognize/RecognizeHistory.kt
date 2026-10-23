package com.freease.music.data.recognize

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Daftar lagu yang pernah dikenali (maks. 100, terbaru di atas). */
class RecognizeHistory(context: Context) {
    private val prefs = context.getSharedPreferences("recognized", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecognizedSong>> = _items.asStateFlow()

    private fun load(): List<RecognizedSong> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecognizedSong(
                o.getString("t"), o.optString("a"), o.optString("c").ifEmpty { null },
                o.optString("al").ifEmpty { null }, o.optString("y").ifEmpty { null },
                recognizedAt = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    fun add(s: RecognizedSong) {
        val list = (listOf(s) + _items.value.filterNot { it.title == s.title && it.artist == s.artist }).take(100)
        save(list)
    }

    fun remove(s: RecognizedSong) = save(_items.value - s)

    fun clear() = save(emptyList())

    private fun save(list: List<RecognizedSong>) {
        _items.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("t", it.title).put("a", it.artist).put("c", it.coverUrl ?: "")
                    .put("al", it.album ?: "").put("y", it.year ?: "").put("at", it.recognizedAt),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
