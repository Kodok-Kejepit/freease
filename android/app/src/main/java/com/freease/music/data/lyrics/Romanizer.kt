package com.freease.music.data.lyrics

/**
 * Cara baca latin untuk lirik Korea (Revised Romanization, dengan aturan sambung bunyi dasar) dan
 * Jepang (romaji Hepburn untuk hiragana/katakana). Sepenuhnya offline. Baris berkanji dilewati karena
 * butuh kamus besar; lebih baik tidak ditampilkan daripada salah baca.
 */
object Romanizer {
    private val initials = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val vowels = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val finals = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")

    /** Konsonan akhir sebelum suku berawalan ㅇ: (sisa di suku ini, bunyi yang pindah ke suku berikut). */
    private val liaison = arrayOf(
        "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "n" to "", "" to "d", "" to "r",
        "l" to "g", "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "l" to "", "" to "m", "" to "b",
        "p" to "s", "" to "s", "" to "ss", "ng" to "", "" to "j", "" to "ch", "" to "k", "" to "t", "" to "p", "" to "",
    )

    private const val SILENT = 11 // ㅇ sebagai awal suku
    private const val RIEUL = 5 // ㄹ sebagai awal suku
    private const val FINAL_RIEUL = 8 // ㄹ sebagai akhir suku

    private fun isHangul(c: Char) = c in '가'..'힣'
    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー'
    private fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

    /** Cara baca latin [line], atau null kalau tidak ada yang perlu/bisa dibaca latin. */
    fun romanize(line: String): String? {
        val hasHangul = line.any(::isHangul)
        val hasKana = line.any(::isKana)
        if (!hasHangul && !hasKana) return null
        // Jepang dengan kanji: dilewati (lihat keterangan di atas).
        if (hasKana && line.any(::isHan)) return null
        var s = line
        if (hasHangul) s = hangul(s)
        if (hasKana) s = kana(s)
        return s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it != line.trim() }
    }

    // ------------------------------------------------------------------ Korea

    private fun hangul(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isHangul(c)) {
                out.append(c)
                i++
                continue
            }
            // Satu "kata" Hangul: suku-suku berurutan, dibaca dengan aturan sambung bunyi.
            val start = i
            while (i < text.length && isHangul(text[i])) i++
            out.append(word(text.substring(start, i)))
        }
        return out.toString()
    }

    private fun word(w: String): String {
        val n = w.length
        val l = IntArray(n); val v = IntArray(n); val t = IntArray(n)
        for (k in 0 until n) {
            val idx = w[k] - '가'
            l[k] = idx / 588; v[k] = (idx % 588) / 28; t[k] = idx % 28
        }
        val sb = StringBuilder()
        var carried: String? = null // bunyi akhir suku sebelumnya yang pindah ke suku ini
        for (k in 0 until n) {
            val init = when {
                carried != null -> carried
                l[k] == RIEUL && k > 0 && t[k - 1] == FINAL_RIEUL -> "l"
                else -> initials[l[k]]
            }
            carried = null
            sb.append(init).append(vowels[v[k]])
            val next = if (k + 1 < n) l[k + 1] else -1
            when {
                t[k] == 0 -> Unit
                next == SILENT && t[k] != 21 -> {
                    val (stay, move) = liaison[t[k]]
                    sb.append(stay)
                    carried = move
                }
                else -> sb.append(finals[t[k]])
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Jepang

    private val kanaMap: Map<Char, String> = buildMap {
        val rows = listOf(
            "あa", "いi", "うu", "えe", "おo", "かka", "きki", "くku", "けke", "こko", "さsa", "しshi", "すsu", "せse", "そso",
            "たta", "ちchi", "つtsu", "てte", "とto", "なna", "にni", "ぬnu", "ねne", "のno", "はha", "ひhi", "ふfu", "へhe", "ほho",
            "まma", "みmi", "むmu", "めme", "もmo", "やya", "ゆyu", "よyo", "らra", "りri", "るru", "れre", "ろro", "わwa", "ゐi",
            "ゑe", "をo", "んn", "がga", "ぎgi", "ぐgu", "げge", "ごgo", "ざza", "じji", "ずzu", "ぜze", "ぞzo", "だda", "ぢji",
            "づzu", "でde", "どdo", "ばba", "びbi", "ぶbu", "べbe", "ぼbo", "ぱpa", "ぴpi", "ぷpu", "ぺpe", "ぽpo", "ゔvu",
            "ぁa", "ぃi", "ぅu", "ぇe", "ぉo", "ゃya", "ゅyu", "ょyo", "ゎwa",
        )
        for (r in rows) {
            val h = r[0]
            put(h, r.substring(1))
            put(h + 0x60, r.substring(1)) // katakana padanannya
        }
    }

    private fun isSmallY(c: Char) = c in "ゃゅょャュョ"
    private fun isSmallVowel(c: Char) = c in "ぁぃぅぇぉァィゥェォ"
    private fun isSokuon(c: Char) = c == 'っ' || c == 'ッ'

    private fun kana(text: String): String {
        val out = StringBuilder()
        var double = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isKana(c)) {
                double = false
                out.append(c)
                i++
                continue
            }
            if (isSokuon(c)) {
                double = true
                i++
                continue
            }
            if (c == 'ー') {
                out.lastOrNull { it in "aiueo" }?.let { out.append(it) }
                i++
                continue
            }
            var r = kanaMap[c] ?: c.toString()
            val nextC = text.getOrNull(i + 1)
            if (nextC != null && isSmallY(nextC) && r.endsWith("i") && r.length > 1) {
                // きゃ → kya, しゃ → sha, ちゃ → cha, じゃ → ja
                val y = kanaMap[nextC]!!
                r = when (r) {
                    "shi", "chi", "ji" -> r.dropLast(1) + y.drop(1)
                    else -> r.dropLast(1) + y
                }
                i++
            } else if (nextC != null && isSmallVowel(nextC) && r.length > 1) {
                // ファ → fa, ティ → ti, ウィ → wi
                val base = when (r) {
                    "tsu" -> "ts"
                    "shi", "chi", "ji" -> r.dropLast(1)
                    else -> r.dropLast(1)
                }
                r = base + kanaMap[nextC]!!
                i++
            } else if (nextC != null && isSmallVowel(nextC) && r == "u") {
                r = "w" + kanaMap[nextC]!!
                i++
            }
            if (r == "n" && text.getOrNull(i + 1)?.let { kanaMap[it]?.firstOrNull() } in listOf('a', 'i', 'u', 'e', 'o', 'y')) r = "n'"
            if (double && r.isNotEmpty() && r[0] !in "aiueon") {
                out.append(if (r.startsWith("ch")) 't' else r[0])
            }
            double = false
            out.append(r)
            i++
        }
        return out.toString()
    }
}
