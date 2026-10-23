package com.freease.music.data.model

/**
 * Merapikan judul video YouTube supaya terlihat seperti judul lagu:
 * "Dewa 19 - Kangen (Official Audio) | GP Records" → artis "Dewa 19", judul "Kangen".
 */
object TitleCleaner {
    private val noiseWords =
        "official|video|audio|lyrics?|lirik|mv|m/v|visuali[sz]er|hd|hq|4k|8k|music|clip|klip|" +
            "full|version|remaster(ed)?|explicit|live session|performance video|teaser|trailer"
    private val bracketNoise = Regex(
        "\\s*[(\\[【「][^)\\]】」]*\\b($noiseWords)\\b[^)\\]】」]*[)\\]】」]",
        RegexOption.IGNORE_CASE,
    )
    private val pipeTail = Regex("\\s*[|｜].*$")
    private val hashtags = Regex("\\s*#\\S+")
    private val dashSplit = Regex("[\\s\\u00A0]+[-–—−‐][\\s\\u00A0]+")
    private val spaces = Regex("\\s{2,}")
    private val artistNoise = Regex("(\\s*-\\s*Topic$)|(VEVO$)|(\\s*\\(?Official\\)?$)", RegexOption.IGNORE_CASE)

    fun cleanArtist(name: String): String = name.replace(artistNoise, "").trim().ifEmpty { name }

    fun cleanTitle(title: String): String {
        var t = title.replace(bracketNoise, "").replace(pipeTail, "").replace(hashtags, "")
        // Sisa pemisah di ujung ("Dewa 19 - Kangen - (Lirik)" → "Dewa 19 - Kangen").
        t = t.replace(spaces, " ").trim().trim(*edgeSeparators).trim()
        return t.ifEmpty { title }
    }

    private val edgeSeparators = charArrayOf('-', '–', '—', '−', '‐', '|', '｜', ':', '~', '•', ' ', '\u00A0')

    /** Hasil: Pair(judul, artis). */
    fun split(rawTitle: String, uploader: String): Pair<String, String> {
        val cleanedUploader = cleanArtist(uploader)
        val title = cleanTitle(rawTitle)
        if (uploader.endsWith(" - Topic")) return title to cleanedUploader
        val parts = title.split(dashSplit, limit = 2)
        if (parts.size == 2) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                val up = cleanedUploader.lowercase()
                // Format "Judul - Artis": sisi kanan sama dengan nama channel.
                if (up.isNotEmpty() && right.lowercase() == up && left.lowercase() != up) return left to right
                if (left.length <= 48) return right to left
            }
        }
        return title to cleanedUploader
    }
}
