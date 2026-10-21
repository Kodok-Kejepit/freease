package com.freease.music.ui.screens

import androidx.compose.ui.graphics.Color

data class Mood(val title: String, val query: String, val color: Color)

/** Kategori jelajah (dipakai di Beranda & Cari), seperti "Jelajahi semua" di Spotify. */
val Moods = listOf(
    Mood("Pop Indonesia", "lagu pop indonesia terbaru", Color(0xFFE13300)),
    Mood("Galau", "lagu galau indonesia", Color(0xFF4B3FB8)),
    Mood("Santai", "chill music playlist", Color(0xFF148A08)),
    Mood("Lo-fi", "lofi hip hop beats", Color(0xFF7358FF)),
    Mood("Workout", "workout music playlist", Color(0xFFE8115B)),
    Mood("Fokus", "focus music instrumental", Color(0xFF1E3264)),
    Mood("Tidur", "sleep music relaxing", Color(0xFF27856A)),
    Mood("K-Pop", "kpop hits", Color(0xFFDC148C)),
    Mood("Dangdut", "dangdut koplo terbaru", Color(0xFFBA5D07)),
    Mood("Rock", "rock indonesia legendaris", Color(0xFF8D67AB)),
    Mood("Jazz", "jazz playlist", Color(0xFF477D95)),
    Mood("Hip-Hop", "hip hop hits", Color(0xFFBC5900)),
    Mood("Akustik", "acoustic cover songs", Color(0xFF8C1932)),
    Mood("Indie", "indie indonesia", Color(0xFF056952)),
    Mood("Religi", "lagu religi islami", Color(0xFF509BF5)),
    Mood("90-an & 2000-an", "lagu indonesia 2000an", Color(0xFFAF2896)),
    Mood("EDM", "edm hits", Color(0xFF0D73EC)),
    Mood("Anime", "anime songs opening", Color(0xFFE91429)),
    Mood("Klasik", "classical music best", Color(0xFF7D4B32)),
    Mood("Romantis", "lagu romantis", Color(0xFFF037A5)),
)
