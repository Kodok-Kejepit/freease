package com.freease.music.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.freease.music.data.model.Song
import com.freease.music.ui.components.PillButton
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Gaya warna kartu lirik. */
class CardStyle(val top: Int, val bottom: Int, val text: Int)

/** Menggambar kartu lirik 1080×1350 (pas untuk story & feed) dengan Canvas Android. */
object LyricsCardRenderer {
    const val W = 1080
    const val H = 1350

    private fun bold(): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD

    fun render(song: Song, lines: List<String>, style: CardStyle, art: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), style.top, style.bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        val pad = 88f
        val artSize = 150f
        // Sampul bersudut membulat.
        if (art != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = artSize / minOf(art.width, art.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(pad - (art.width * scale - artSize) / 2, pad - (art.height * scale - artSize) / 2)
            }
            shader.setLocalMatrix(m)
            p.shader = shader
            c.drawRoundRect(RectF(pad, pad, pad + artSize, pad + artSize), 22f, 22f, p)
        }
        val textX = if (art != null) pad + artSize + 36f else pad
        val maxTitle = W - textX - pad
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.text; textSize = 46f; typeface = bold() }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(style.text, 190); textSize = 38f
        }
        c.drawText(TextUtils.ellipsize(song.title, titlePaint, maxTitle, TextUtils.TruncateAt.END).toString(), textX, pad + 64f, titlePaint)
        c.drawText(TextUtils.ellipsize(song.artist, artistPaint, maxTitle, TextUtils.TruncateAt.END).toString(), textX, pad + 120f, artistPaint)

        // Baris lirik: ukuran huruf mengecil sampai muat di area tengah.
        val body = lines.joinToString("\n")
        val top = pad + artSize + 90f
        val bottomLimit = H - pad - 110f
        val width = (W - pad * 2).toInt()
        var size = 76f
        var layout: StaticLayout
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.text; typeface = bold() }
        while (true) {
            lp.textSize = size
            layout = StaticLayout.Builder.obtain(body, 0, body.length, lp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .build()
            if (layout.height <= bottomLimit - top || size <= 34f) break
            size -= 4f
        }
        c.save()
        c.translate(pad, top + ((bottomLimit - top) - layout.height).coerceAtLeast(0f) / 3)
        layout.draw(c)
        c.restore()

        // Tanda kecil Freease di bawah.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(style.text, 200); textSize = 36f; typeface = bold()
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(style.text, 220) }
        c.drawCircle(pad + 18f, H - pad + 2f, 18f, dot)
        c.drawText("Freease", pad + 50f, H - pad + 14f, brand)
        return bmp
    }

    /** Simpan ke cache lalu buka lembar bagikan Android. */
    fun share(context: Context, bmp: Bitmap, caption: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "lirik-${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Bagikan kartu lirik").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Pratinjau kartu lirik + pilihan warna + tombol bagikan. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLyricsSheet(song: Song, lines: List<String>, baseColor: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accent = Freease.accent
    val styles = remember(baseColor) {
        val b = baseColor.toArgb()
        listOf(
            CardStyle(ColorUtils.blendARGB(b, android.graphics.Color.WHITE, 0.08f), ColorUtils.blendARGB(b, android.graphics.Color.BLACK, 0.45f), android.graphics.Color.WHITE),
            CardStyle(accent.primary.toArgb(), accent.secondary.toArgb(), android.graphics.Color.WHITE),
            CardStyle(0xFF15151E.toInt(), 0xFF050508.toInt(), android.graphics.Color.WHITE),
            CardStyle(0xFFF6F1E9.toInt(), 0xFFE7DED0.toInt(), 0xFF141414.toInt()),
        )
    }
    var styleIndex by remember { mutableIntStateOf(0) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.id) {
        art = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(song.artworkSmall).allowHardware(false).size(300).build()
                (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
        }
    }
    LaunchedEffect(styleIndex, art, lines) {
        preview = withContext(Dispatchers.Default) { LyricsCardRenderer.render(song, lines, styles[styleIndex], art) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Freease.colors.elevated) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth(0.72f).aspectRatio(LyricsCardRenderer.W / LyricsCardRenderer.H.toFloat()).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                val p = preview
                if (p != null) Image(p.asImageBitmap(), "Pratinjau kartu lirik", Modifier.fillMaxWidth())
                else CircularProgressIndicator(color = accent.primary)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                styles.forEachIndexed { i, s ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(s.top), Color(s.bottom))))
                            .border(2.dp, if (i == styleIndex) Color.White else Color.Transparent, CircleShape)
                            .clickable { styleIndex = i },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Bagikan gambar", {
                    preview?.let { LyricsCardRenderer.share(context, it, "${song.title} — ${song.artist}") }
                    onDismiss()
                })
                PillButton("Bagikan teks", {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n") + "\n\n${song.title} — ${song.artist}")
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }, filled = false)
            }
            Spacer(Modifier.height(20.dp))
            Text("Pilih warna, lalu bagikan ke story atau chat.", style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
            Spacer(Modifier.height(16.dp))
        }
    }
}
