package com.freease.music.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.freease.music.data.monthly.MonthlyReport
import java.io.File

/** Kartu rekap bulanan sebagai gambar (1080×1350) untuk dibagikan. Digambar manual, tanpa emoji. */
object MonthlyCard {
    private const val W = 1080
    private const val H = 1350

    fun render(r: MonthlyReport, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dark = 0xFF0A0A10.toInt()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W * 0.3f, H.toFloat(), ColorUtils.blendARGB(accent, dark, 0.35f), dark, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        fun paint(size: Float, bold: Boolean, color: Int = 0xFFFFFFFF.toInt(), alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            this.color = color
            this.alpha = alpha
        }
        fun text(s: String, x: Float, y: Float, p: TextPaint, maxW: Float = W - 2 * x) {
            c.drawText(TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString(), x, y, p)
        }

        val pad = 80f
        text("MONTHLY FREEASE", pad, 130f, paint(36f, true, alpha = 200))
        text(r.month.label, pad, 190f, paint(52f, true))

        val minutes = "%,d".format(r.insights.totalMin).replace(',', '.')
        text(minutes, pad, 430f, paint(190f, true))
        text("menit mendengarkan", pad, 490f, paint(44f, false, alpha = 220))

        text(r.persona.title, pad, 600f, paint(64f, true))
        text(r.persona.blurb, pad, 656f, paint(34f, false, alpha = 200))

        val line = Paint().apply { color = 0x33FFFFFF; strokeWidth = 2f }
        c.drawLine(pad, 720f, W - pad, 720f, line)

        var y = 790f
        text("LAGU TERATAS", pad, y, paint(30f, true, alpha = 190))
        y += 20f
        r.topSongs.take(3).forEachIndexed { i, s ->
            y += 70f
            text("${i + 1}", pad, y, paint(46f, true, alpha = 230))
            text(s.song.title, pad + 70f, y, paint(42f, true), W - pad - (pad + 70f))
        }

        y += 100f
        text("ARTIS TERATAS", pad, y, paint(30f, true, alpha = 190))
        y += 20f
        r.topArtists.take(3).forEachIndexed { i, a ->
            y += 70f
            text("${i + 1}", pad, y, paint(46f, true, alpha = 230))
            text(a.artist, pad + 70f, y, paint(42f, true), W - pad - (pad + 70f))
        }

        val earned = r.achievements.count { it.earned }
        text(
            "${r.distinctSongs} lagu  •  ${r.distinctArtists} artis  •  $earned pencapaian",
            pad, H - 150f, paint(36f, false, alpha = 230),
        )
        text("Freease", pad, H - 80f, paint(44f, true))
        return bmp
    }

    /** Menyimpan kartu ke cache lalu membuat intent berbagi gambar. Panggil dari thread latar. */
    fun shareIntent(context: Context, r: MonthlyReport, accent: Int): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "monthly_${r.month.year}_${r.month.month + 1}.png")
        val bmp = render(r, accent)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "Monthly Freease ${r.month.label}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Bagikan rekap",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
