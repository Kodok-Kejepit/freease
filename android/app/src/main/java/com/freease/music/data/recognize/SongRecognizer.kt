package com.freease.music.data.recognize

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

/** Hasil pengenalan lagu. */
data class RecognizedSong(
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val album: String? = null,
    val year: String? = null,
    /** Posisi cuplikan di dalam lagu (detik), kalau diketahui. */
    val offsetSec: Double? = null,
    val recognizedAt: Long = System.currentTimeMillis(),
) {
    val searchQuery: String get() = "$title $artist"
}

/** Mengirim sidik jari ke layanan pengenal lagu (endpoint publik aplikasi Shazam). */
class SongRecognizer(
    private val client: OkHttpClient,
    private val base64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
) {
    /** null = tidak dikenali. Melempar exception kalau jaringan bermasalah. */
    suspend fun recognize(gen: SignatureGenerator): RecognizedSong? = withContext(Dispatchers.IO) {
        val sig = gen.encode()
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", -6.2).put("longitude", 106.8))
            .put(
                "signature",
                JSONObject()
                    .put("samplems", gen.numberSamples * 1000L / SignatureGenerator.SAMPLE_RATE)
                    .put("timestamp", now)
                    .put("uri", "data:audio/vnd.shazam.sig;base64," + base64(sig)),
            )
            .put("timestamp", now)
            .put("timezone", TimeZone.getDefault().id)
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
            UUID.randomUUID().toString().uppercase() + "/" + UUID.randomUUID() +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)")
            .header("Content-Language", "en_US")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("Server pengenal lagu menolak (${res.code})")
            parse(res.body?.string().orEmpty())
        }
    }

    companion object {
        fun parse(json: String): RecognizedSong? {
            val o = JSONObject(json)
            val track = o.optJSONObject("track") ?: return null
            val title = track.optString("title").takeIf { it.isNotBlank() } ?: return null
            val images = track.optJSONObject("images")
            var album: String? = null
            var year: String? = null
            val sections = track.optJSONArray("sections")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val meta = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                    for (k in 0 until meta.length()) {
                        val m = meta.optJSONObject(k) ?: continue
                        when (m.optString("title")) {
                            "Album" -> album = m.optString("text").ifBlank { null }
                            "Released" -> year = m.optString("text").ifBlank { null }
                        }
                    }
                }
            }
            val offset = o.optJSONArray("matches")?.optJSONObject(0)?.optDouble("offset")?.takeIf { !it.isNaN() }
            return RecognizedSong(
                title = title,
                artist = track.optString("subtitle"),
                coverUrl = images?.optString("coverarthq")?.ifBlank { null } ?: images?.optString("coverart")?.ifBlank { null },
                album = album,
                year = year,
                offsetSec = offset,
            )
        }
    }
}
