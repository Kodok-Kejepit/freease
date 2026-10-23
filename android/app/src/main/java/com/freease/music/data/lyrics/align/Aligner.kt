package com.freease.music.data.lyrics.align

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pemetaan waktu rekaman acuan (versi lagu, tempat waktu lirik dibuat) → waktu video yang diputar.
 * Tiap [Segment] berlaku untuk rentang waktu acuan tertentu; bagian acuan yang tidak ada di video
 * (dipotong) tidak punya segmen dan dipetakan ke null.
 */
class AlignmentMap(val segments: List<Segment>, val confidence: Double, val videoDurationMs: Long) {
    class Segment(val fromMs: Long, val toMs: Long, val shiftMs: Long)

    /** Waktu di video untuk waktu acuan [refMs]; null kalau bagian itu tidak ada di video. */
    fun map(refMs: Long): Long? {
        val s = segments.firstOrNull { refMs >= it.fromMs && refMs < it.toMs } ?: return null
        return (refMs + s.shiftMs).takeIf { it in 0..videoDurationMs }
    }
}

/**
 * Mencari [AlignmentMap] antara dua [AudioFeatures] dari rekaman yang sama (mis. versi audio resmi vs
 * video klip yang punya intro, adegan sisipan, atau potongan berbeda). Murni hitungan, tanpa Android,
 * supaya bisa diuji di JVM.
 */
object Aligner {
    private const val WINDOW_SEC = 8.0
    private const val HOP_SEC = 2.0
    private const val CANDIDATES = 8
    private const val REFINE_FRAMES = 3

    /** Kemiripan minimum jendela dianggap cocok (kosinus fitur onset, 0..1). */
    private const val MATCH = 0.42

    /** Hukuman berpindah pergeseran antarjendela; mencegah salah lompat ke reff yang mirip. */
    private const val SWITCH = 0.35

    /** Ambang keyakinan: porsi bagian lirik yang cocok dan rata-rata kemiripannya. */
    private const val MIN_COVERAGE = 0.6
    private const val MIN_MEAN = 0.5

    /**
     * @param refRangeMs rentang acuan yang penting (dari baris lirik pertama sampai terakhir) untuk
     *   menghitung keyakinan. Null = seluruh lagu.
     * @return null kalau rekamannya bukan rekaman yang sama (atau terlalu berbeda untuk dipercaya).
     */
    fun align(ref: AudioFeatures, video: AudioFeatures, refRangeMs: LongRange? = null): AlignmentMap? {
        require(ref.bands == video.bands) { "Jumlah pita berbeda" }
        val bands = ref.bands
        val frameMs = ref.frameMs
        val r = onsets(ref)
        val v = onsets(video)
        val nr = r.size / bands
        val nv = v.size / bands
        if (nr < 200 || nv < 200) return null

        val candidates = candidateLags(r, v, nr, nv, bands)
        if (candidates.isEmpty()) return null

        // --- skor tiap jendela acuan pada tiap kandidat pergeseran
        val win = (WINDOW_SEC * 1000 / frameMs).toInt()
        val hop = (HOP_SEC * 1000 / frameMs).toInt()
        val starts = (0..max(0, nr - win) step hop).toList()
        val k = candidates.size
        val score = Array(starts.size) { FloatArray(k) { -1f } }
        val lagOf = Array(starts.size) { IntArray(k) }
        val silent = BooleanArray(starts.size)
        for ((wi, a) in starts.withIndex()) {
            val len = min(win, nr - a)
            silent[wi] = norm(r, a, len, bands) < 1e-3
            for (j in 0 until k) {
                var best = -1f
                var bestLag = candidates[j]
                for (d in -REFINE_FRAMES..REFINE_FRAMES) {
                    val lag = candidates[j] + d
                    val s = cosine(r, a, v, a + lag, len, bands, nv)
                    if (s > best) {
                        best = s
                        bestLag = lag
                    }
                }
                score[wi][j] = best
                lagOf[wi][j] = bestLag
            }
        }

        // --- Viterbi: status = salah satu kandidat atau "tidak ada di video" (indeks k)
        val states = k + 1
        val dp = Array(starts.size) { FloatArray(states) }
        val back = Array(starts.size) { IntArray(states) }
        fun emit(wi: Int, s: Int): Float = when {
            silent[wi] -> 0f
            s == k -> MATCH.toFloat()
            else -> score[wi][s]
        }
        for (s in 0 until states) dp[0][s] = emit(0, s)
        for (wi in 1 until starts.size) {
            for (s in 0 until states) {
                var best = Float.NEGATIVE_INFINITY
                var arg = 0
                for (p in 0 until states) {
                    val pen = when {
                        p == s -> 0f
                        p == k || s == k -> (SWITCH / 2).toFloat()
                        candidates[s] < candidates[p] -> (SWITCH * 2).toFloat() // video mundur: jarang
                        else -> SWITCH.toFloat()
                    }
                    val vv = dp[wi - 1][p] - pen
                    if (vv > best) {
                        best = vv
                        arg = p
                    }
                }
                dp[wi][s] = best + emit(wi, s)
                back[wi][s] = arg
            }
        }
        val path = IntArray(starts.size)
        path[starts.lastIndex] = (0 until states).maxBy { dp[starts.lastIndex][it] }
        for (wi in starts.lastIndex downTo 1) path[wi - 1] = back[wi][path[wi]]

        // --- keyakinan: porsi jendela (bersuara, dalam rentang lirik) yang cocok + rata-rata kemiripan
        val range = refRangeMs?.let { (it.first / frameMs).toInt()..(it.last / frameMs).toInt() } ?: 0..nr
        var considered = 0
        var matched = 0
        var sum = 0.0
        for ((wi, a) in starts.withIndex()) {
            val center = a + win / 2
            if (silent[wi] || center !in range) continue
            considered++
            if (path[wi] != k) {
                matched++
                sum += score[wi][path[wi]]
            }
        }
        if (considered == 0 || matched == 0) return null
        val coverage = matched.toDouble() / considered
        val mean = sum / matched
        lastStats = "coverage=%.2f mean=%.2f (ditolak)".format(coverage, mean)
        if (coverage < MIN_COVERAGE || mean < MIN_MEAN) return null

        // --- jendela → run berpergeseran sama. Jendela "tidak cocok" di ujung atau di antara dua run
        // berpergeseran sama hanya bagian yang kurang jelas (mis. fade-out), jadi diisi tetangganya.
        class Run(val from: Int, var to: Int, val lag: Int)
        val runs = mutableListOf<Run>()
        for ((wi, a) in starts.withIndex()) {
            if (path[wi] == k) continue
            val lag = lagOf[wi][path[wi]]
            val center = a + win / 2
            val last = runs.lastOrNull()
            if (last != null && abs(last.lag - lag) <= REFINE_FRAMES) last.to = center else runs += Run(center, center, lag)
        }
        if (runs.isEmpty()) return null

        // Batas antardua run dicari per frame; kalau video memotong sebagian lagu, bagian itu
        // tidak dipetakan ke mana pun (liriknya dilewati, bukan ditampilkan di waktu yang salah).
        val typical = typicalFrameDot(r, v, runs.map { Triple(it.from, it.to, it.lag) }, bands, nv)
        val segFrom = IntArray(runs.size)
        val segTo = IntArray(runs.size)
        segFrom[0] = Int.MIN_VALUE / 4
        segTo[runs.lastIndex] = Int.MAX_VALUE / 4
        for (i in 1 until runs.size) {
            val (s1, s2) = boundary(r, v, runs[i - 1].to, runs[i].from, runs[i - 1].lag, runs[i].lag, bands, nv, typical * 0.25)
            segTo[i - 1] = s1
            segFrom[i] = s2
        }
        val segments = runs.indices.mapNotNull { i ->
            if (segTo[i] <= segFrom[i]) return@mapNotNull null
            AlignmentMap.Segment(
                fromMs = (segFrom[i] * frameMs).roundToLong(),
                toMs = (segTo[i] * frameMs).roundToLong(),
                shiftMs = (runs[i].lag * frameMs).roundToLong(),
            )
        }
        if (segments.isEmpty()) return null
        lastStats = "coverage=%.2f mean=%.2f".format(coverage, mean)
        return AlignmentMap(segments, coverage * mean, (nv * frameMs).roundToLong())
    }

    /** Statistik pencocokan terakhir (untuk diagnosis di tes). */
    @Volatile
    var lastStats: String = ""
        private set

    /** Rata-rata kemiripan per frame di dalam run yang cocok — dasar ambang "bagian terpotong". */
    private fun typicalFrameDot(r: FloatArray, v: FloatArray, runs: List<Triple<Int, Int, Int>>, b: Int, nv: Int): Double {
        var sum = 0.0
        var n = 0
        for ((from, to, lag) in runs) for (t in from until to step 5) {
            sum += frameDot(r, t, v, t + lag, b, nv)
            n++
        }
        return if (n == 0) 1.0 else max(0.1, sum / n)
    }

    /**
     * Antara frame [from]..[to]: bagian awal ikut pergeseran A, bagian akhir ikut B, dan di tengahnya
     * boleh ada bagian yang tidak cocok dengan keduanya (terpotong di video). Mengembalikan (akhir A, awal B).
     */
    private fun boundary(
        r: FloatArray, v: FloatArray, from: Int, to: Int, lagA: Int, lagB: Int, b: Int, nv: Int, tau: Double,
    ): Pair<Int, Int> {
        if (to <= from) return from to from
        val len = to - from
        val pa = DoubleArray(len + 1)
        val pb = DoubleArray(len + 1)
        for (i in 0 until len) {
            val t = from + i
            pa[i + 1] = pa[i] + frameDot(r, t, v, t + lagA, b, nv)
            pb[i + 1] = pb[i] + frameDot(r, t, v, t + lagB, b, nv)
        }
        // Maksimalkan pa[s1] + tau*(s2-s1) + (pb[len]-pb[s2]) dengan s1 <= s2.
        var bestHead = Double.NEGATIVE_INFINITY
        var bestHeadAt = 0
        var best = Double.NEGATIVE_INFINITY
        var arg = 0 to 0
        for (s2 in 0..len) {
            val head = pa[s2] - tau * s2
            if (head > bestHead) {
                bestHead = head
                bestHeadAt = s2
            }
            val total = bestHead + tau * s2 + (pb[len] - pb[s2])
            if (total > best) {
                best = total
                arg = bestHeadAt to s2
            }
        }
        return (from + arg.first) to (from + arg.second)
    }

    /**
     * Fitur onset: kenaikan energi log tiap pita (beda volume/EQ antarversi jadi hilang), lalu
     * dinormalkan per pita.
     */
    private fun onsets(f: AudioFeatures): FloatArray {
        val b = f.bands
        val n = f.frames
        val out = FloatArray(n * b)
        for (t in 1 until n) for (j in 0 until b) {
            out[t * b + j] = max(0f, f.data[t * b + j] - f.data[(t - 1) * b + j])
        }
        for (j in 0 until b) {
            var mean = 0.0
            for (t in 0 until n) mean += out[t * b + j]
            mean /= n
            var varSum = 0.0
            for (t in 0 until n) {
                val d = out[t * b + j] - mean
                varSum += d * d
            }
            val sd = sqrt(varSum / n).takeIf { it > 1e-6 } ?: 1.0
            for (t in 0 until n) out[t * b + j] = ((out[t * b + j] - mean) / sd).toFloat()
        }
        return out
    }

    private fun norm(x: FloatArray, start: Int, len: Int, b: Int): Double {
        var s = 0.0
        for (i in start * b until (start + len) * b) s += x[i] * x[i]
        return s / (len * b)
    }

    /** Kosinus antara jendela acuan [ra, ra+len) dan video [va, va+len); bagian di luar video = 0. */
    private fun cosine(r: FloatArray, ra: Int, v: FloatArray, va: Int, len: Int, b: Int, nv: Int): Float {
        var dot = 0.0
        var rr = 0.0
        var vv = 0.0
        for (t in 0 until len) {
            val vt = va + t
            val ri = (ra + t) * b
            if (vt < 0 || vt >= nv) {
                for (j in 0 until b) rr += r[ri + j] * r[ri + j]
                continue
            }
            val vi = vt * b
            for (j in 0 until b) {
                val x = r[ri + j]
                val y = v[vi + j]
                dot += x * y
                rr += x * x
                vv += y * y
            }
        }
        if (rr <= 0 || vv <= 0) return 0f
        return (dot / sqrt(rr * vv)).toFloat()
    }

    private fun frameDot(r: FloatArray, t: Int, v: FloatArray, vt: Int, b: Int, nv: Int): Double {
        if (vt < 0 || vt >= nv) return 0.0
        var s = 0.0
        for (j in 0 until b) s += r[t * b + j] * v[vt * b + j]
        return s
    }

    /**
     * Kandidat pergeseran (dalam frame) dari korelasi silang seluruh lagu lewat FFT. Diambil beberapa
     * puncak tertinggi yang saling berjauhan, karena video bisa punya lebih dari satu pergeseran.
     */
    private fun candidateLags(r: FloatArray, v: FloatArray, nr: Int, nv: Int, b: Int): IntArray {
        var m = 1
        while (m < nr + nv) m = m shl 1
        val fft = Fft(m)
        val accRe = FloatArray(m)
        val accIm = FloatArray(m)
        val ar = FloatArray(m)
        val ai = FloatArray(m)
        val br = FloatArray(m)
        val bi = FloatArray(m)
        for (j in 0 until b) {
            ar.fill(0f); ai.fill(0f); br.fill(0f); bi.fill(0f)
            for (t in 0 until nr) ar[t] = r[t * b + j]
            for (t in 0 until nv) br[t] = v[t * b + j]
            fft.transform(ar, ai)
            fft.transform(br, bi)
            // corr[k] = sum r[t] v[t+k]  →  conj(R) * V
            for (i in 0 until m) {
                accRe[i] += ar[i] * br[i] + ai[i] * bi[i]
                accIm[i] += ar[i] * bi[i] - ai[i] * br[i]
            }
        }
        fft.transform(accRe, accIm, inverse = true)
        // Lag k ∈ [-(nr-1), nv-1]; indeks negatif ada di ujung array. Dinormalkan dengan panjang tumpang.
        val lags = IntArray(nr + nv - 1) { it - (nr - 1) }
        val values = DoubleArray(lags.size) { i ->
            val k = lags[i]
            val idx = if (k >= 0) k else m + k
            val overlap = min(nr, nv - k) - max(0, -k)
            if (overlap < 250) Double.NEGATIVE_INFINITY else accRe[idx] / m / sqrt(overlap.toDouble())
        }
        val order = values.indices.sortedByDescending { values[it] }
        val picked = mutableListOf<Int>()
        val minSep = 50 // 1 detik
        for (i in order) {
            if (values[i] == Double.NEGATIVE_INFINITY) break
            val k = lags[i]
            if (picked.all { abs(it - k) >= minSep }) picked += k
            if (picked.size == CANDIDATES) break
        }
        return picked.toIntArray()
    }
}

/**
 * Lirik dengan waktu yang sudah dipetakan ke video. Baris di bagian yang tidak ada di video, atau yang
 * akan membuat urutan mundur, dilewati. Null kalau terlalu banyak baris hilang (pemetaan meragukan).
 */
fun com.freease.music.data.model.Lyrics.alignedWith(map: AlignmentMap): com.freease.music.data.model.Lyrics? {
    val src = synced ?: return null
    val out = ArrayList<com.freease.music.data.model.LyricLine>(src.size)
    var last = Long.MIN_VALUE
    for (line in src) {
        val t = map.map(line.timeMs) ?: continue
        if (t < last) continue
        // Kata-kata ikut bergeser sejauh barisnya (satu baris tidak terpotong di tengah).
        out += line.copy(timeMs = t, words = line.words?.map { it.copy(timeMs = it.timeMs + (t - line.timeMs)) })
        last = t
    }
    val sung = src.count { it.text.isNotBlank() }
    if (out.count { it.text.isNotBlank() } < sung * 0.6) return null
    return copy(
        synced = out,
        timingTrusted = true,
        autoSync = com.freease.music.data.model.Lyrics.AutoSync.DONE,
        offsetMs = 0,
    )
}
