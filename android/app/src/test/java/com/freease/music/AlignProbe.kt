package com.freease.music

import com.freease.music.data.lyrics.align.AlignmentMap
import com.freease.music.data.lyrics.align.AudioFeatureExtractor
import com.freease.music.data.lyrics.align.AudioFeatures
import com.freease.music.data.lyrics.align.Aligner
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uji penyelaras dengan audio sungguhan yang sudah didekode ke PCM 16-bit (mis. lewat ffmpeg).
 * Hanya jalan kalau ada file `align.on` berisi path folder kasus uji.
 * Nama berkas: `<nama>.<samplerate>.<kanal>.pcm`.
 */
class AlignProbe {
    private fun features(f: File): AudioFeatures {
        val (rate, ch) = f.name.split('.').let { it[1].toInt() to it[2].toInt() }
        val ex = AudioFeatureExtractor(rate, ch)
        val buf = ShortArray(8192)
        f.inputStream().buffered(1 shl 16).use { input ->
            val bytes = ByteArray(buf.size * 2)
            while (true) {
                val n = input.read(bytes)
                if (n <= 0) break
                val sb = ByteBuffer.wrap(bytes, 0, n - n % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val cnt = sb.remaining()
                sb.get(buf, 0, cnt)
                ex.feed(buf, cnt)
            }
        }
        return ex.finish()
    }

    private fun describe(m: AlignmentMap?) = m?.let {
        "conf=%.2f ".format(it.confidence) + it.segments.joinToString(" | ") { s ->
            "[${if (s.fromMs < -1_000_000) "-∞" else s.fromMs}..${if (s.toMs > 1_000_000_000) "∞" else s.toMs}) ${s.shiftMs}ms"
        }
    } ?: "DITOLAK (bukan rekaman sama)"

    @Test
    fun probe() {
        val on = File("align.on")
        Assume.assumeTrue(on.exists())
        val dir = File(on.readText().trim())
        val pcm = dir.listFiles()!!.filter { it.name.endsWith(".pcm") }.associateBy { it.name.substringBefore('.') }
        val out = StringBuilder()
        // nama kasus → (acuan, daftar (waktu acuan ms, waktu video yang diharapkan ms atau null))
        val cases = linkedMapOf<String, Pair<String, List<Pair<Long, Long?>>>>(
            "v1" to ("r1" to listOf(5_000L to 19_300L, 60_000L to 74_300L, 200_000L to 214_300L, 270_000L to 284_300L)),
            "v2" to ("r2" to listOf(10_000L to 7_000L, 70_000L to 67_000L, 85_000L to 89_500L, 190_000L to 194_500L)),
            "v3" to ("r1" to listOf(1_000L to 1_370L, 100_000L to 100_370L, 260_000L to 260_370L)),
            "v4" to ("r1" to emptyList()),
            "v5" to ("r1" to listOf(10_000L to 15_500L, 55_000L to 60_500L, 250_000L to 255_500L)),
            "v6" to ("r2" to listOf(50_000L to 52_000L, 118_000L to 120_000L, 125_000L to null, 140_000L to 127_000L, 190_000L to 177_000L)),
            "v7" to ("r1" to listOf(3_000L to 34_200L, 150_000L to 181_200L, 265_000L to 296_200L)),
        )
        val cache = HashMap<String, AudioFeatures>()
        for ((v, spec) in cases) {
            val (r, checks) = spec
            val t0 = System.currentTimeMillis()
            val rf = cache.getOrPut(r) { features(pcm.getValue(r)) }
            val vf = cache.getOrPut(v) { features(pcm.getValue(v)) }
            val t1 = System.currentTimeMillis()
            val m = Aligner.align(rf, vf)
            val t2 = System.currentTimeMillis()
            out.appendLine("   ${Aligner.lastStats}")
            out.appendLine("$v vs $r: fitur ${t1 - t0}ms, cocok ${t2 - t1}ms, frame=%.2fms -> ${describe(m)}".format(rf.frameMs))
            for ((ref, want) in checks) {
                val got = m?.map(ref)
                val err = if (got != null && want != null) got - want else null
                out.appendLine("   acuan ${ref}ms -> video ${got}ms (harus ${want}ms, selisih ${err}ms)")
            }
        }
        File("build/align.txt").writeText(out.toString())
    }
}
