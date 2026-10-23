package com.freease.music.playback.loudness

import org.json.JSONObject
import java.io.File

/**
 * Loudness (LUFS) tiap lagu yang pernah diputar sampai cukup panjang, supaya putaran berikutnya
 * langsung memakai gain yang tepat sejak detik pertama. Disimpan di satu berkas JSON kecil.
 */
class LoudnessStore(private val file: File) {
    private val map = object : LinkedHashMap<String, Double>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX
    }
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            o.keys().forEach { k -> map[k] = o.getDouble(k) }
        }
    }

    @Synchronized
    fun get(id: String): Double? = map[id]

    @Synchronized
    fun put(id: String, lufs: Double) {
        map[id] = Math.round(lufs * 10) / 10.0
    }

    @Synchronized
    fun save() {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX = 5000
    }
}
