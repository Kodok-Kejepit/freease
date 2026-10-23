package com.freease.music.playback

import android.content.Context
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Kecepatan & nada yang diingat per lagu (mis. podcast 1,5×, lagu karaoke turun 2 kunci).
 * Lagu tanpa setelan khusus memakai [base] — setelan umum sesi ini.
 */
class SongParams(context: Context) {
    data class Params(val speed: Float, val pitch: Float)

    private val prefs = context.getSharedPreferences("song_params", Context.MODE_PRIVATE)

    /** Setelan umum selama app berjalan (kembali normal setelah app ditutup, seperti sebelumnya). */
    @Volatile var base: Params = Params(1f, 1f)

    fun get(id: String): Params? = prefs.getString("s:$id", null)?.split(',')?.let { p ->
        val s = p.getOrNull(0)?.toFloatOrNull() ?: return null
        val t = p.getOrNull(1)?.toFloatOrNull() ?: return null
        Params(s, t)
    }

    fun remember(id: String, p: Params) {
        val e = prefs.edit().putString("s:$id", "${p.speed},${p.pitch}")
        // Batasi jumlah entri; buang yang tertua (urutan disimpan di daftar sederhana).
        val order = (prefs.getString("order", "") ?: "").split('|').filter { it.isNotEmpty() && it != id } + id
        val keep = order.takeLast(MAX)
        order.dropLast(MAX).forEach { e.remove("s:$it") }
        e.putString("order", keep.joinToString("|")).apply()
    }

    fun forget(id: String) {
        val order = (prefs.getString("order", "") ?: "").split('|').filter { it.isNotEmpty() && it != id }
        prefs.edit().remove("s:$id").putString("order", order.joinToString("|")).apply()
    }

    /** Yang berlaku untuk lagu [id]. */
    fun effective(id: String?): Params = id?.let(::get) ?: base

    companion object {
        const val MAX = 2000
        private const val SEMITONE = 1.0594630943592953

        fun semitones(pitch: Float): Int = (ln(pitch.toDouble()) / ln(SEMITONE)).roundToInt()
        fun pitchOf(semitones: Int): Float = SEMITONE.pow(semitones).toFloat()
    }
}
