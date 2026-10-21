package com.freease.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gambar sampul dengan fallback otomatis (mis. hq720 → mqdefault) dan placeholder bergradasi. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Crop,
    iconSize: Dp = 28.dp,
) {
    var current by remember(url) { mutableStateOf(url) }
    var failed by remember(url) { mutableStateOf(false) }
    val accent = Freease.accent
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.primary.copy(alpha = 0.35f), accent.secondary.copy(alpha = 0.18f), Color(0xFF1C1C28)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null || failed) {
            Icon(
                Icons.Rounded.MusicNote, null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(iconSize),
            )
        }
        if (current != null) {
            AsyncImage(
                model = current,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (fallback != null && current != fallback) current = fallback else failed = true
                },
            )
        }
    }
}

/**
 * Warna dominan artwork (diambil dengan Palette) yang sudah digelapkan supaya teks putih
 * tetap terbaca — dipakai untuk latar player & header seperti Spotify.
 */
@Composable
fun rememberArtworkColor(url: String?, default: Color = Color(0xFF2A2140)): State<Color> {
    val context = LocalContext.current
    var color by remember { mutableStateOf(default) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(url).allowHardware(false).size(160).build()
                val result = context.imageLoader.execute(req) as? SuccessResult ?: return@runCatching null
                val bmp = result.image.toBitmap()
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val swatch = p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch
                swatch?.rgb
            }.getOrNull()
        }
        if (extracted != null) color = Color(tame(extracted))
    }
    return animateColorAsState(color, tween(700), label = "artworkColor")
}

private fun tame(argb: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = (hsl[1] * 1.05f).coerceIn(0.25f, 0.85f)
    hsl[2] = hsl[2].coerceIn(0.22f, 0.36f)
    return ColorUtils.HSLToColor(hsl)
}

fun Color.darken(f: Float): Color = Color(ColorUtils.blendARGB(toArgb(), Color.Black.toArgb(), f))
