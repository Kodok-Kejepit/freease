package com.freease.music.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Thumbs
import com.freease.music.data.monthly.MonthKey
import com.freease.music.data.wrapped.WrappedReport
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.EmptyState
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.util.Format
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** Tahun rekap default: Desember–Januari menampilkan tahun yang baru/sedang berakhir. */
fun defaultWrappedYear(): Int {
    val c = Calendar.getInstance()
    return if (c.get(Calendar.MONTH) == Calendar.JANUARY) c.get(Calendar.YEAR) - 1 else c.get(Calendar.YEAR)
}

private val Slides = listOf(
    listOf(Color(0xFF1ED760), Color(0xFF0B3D2E)),
    listOf(Color(0xFFFF6437), Color(0xFF5A0E2C)),
    listOf(Color(0xFF7B4DFF), Color(0xFF14102E)),
    listOf(Color(0xFFE8115B), Color(0xFF2B0A3D)),
    listOf(Color(0xFF4F9DFF), Color(0xFF0A1F3D)),
    listOf(Color(0xFFFFC94D), Color(0xFF6B2B0A)),
    listOf(Color(0xFF00D1C1), Color(0xFF0B2633)),
    listOf(Color(0xFF7B4DFF), Color(0xFF0A0A10)),
)

/** Freease Wrapped: rekap setahun dalam bentuk cerita yang bisa digeser dan dibagikan. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WrappedScreen(year: Int, startPage: Int = 0) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<WrappedReport?>(null) }
    LaunchedEffect(year) { report = actions.library.wrapped(year) }
    val r = report

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A10))) {
        when {
            r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            r.isEmpty -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.Rounded.Close, "Tutup") }
                EmptyState(
                    Icons.Rounded.Share, "Wrapped $year belum siap",
                    "Dengarkan lebih banyak musik di Freease — rekap tahunanmu akan terisi otomatis.",
                )
            }
            else -> {
                val pages = 8
                val pager = rememberPagerState(initialPage = startPage) { pages }
                val layer = rememberGraphicsLayer()
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    val colors = Slides[page % Slides.size]
                    Box(
                        Modifier.fillMaxSize()
                            .then(
                                if (page == pager.currentPage) {
                                    Modifier.drawWithContent {
                                        layer.record { this@drawWithContent.drawContent() }
                                        drawLayer(layer)
                                    }
                                } else Modifier,
                            )
                            .background(Brush.linearGradient(colors))
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 28.dp)
                            .padding(top = 64.dp, bottom = 96.dp),
                    ) {
                        when (page) {
                            0 -> IntroSlide(r)
                            1 -> MinutesSlide(r)
                            2 -> TopArtistSlide(r)
                            3 -> TopSongsSlide(r)
                            4 -> MonthsSlide(r)
                            5 -> HabitsSlide(r)
                            6 -> PersonaSlide(r)
                            else -> SummarySlide(r)
                        }
                    }
                }
                // Penanda halaman ala story.
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(pages) { i ->
                        Box(
                            Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = if (i <= pager.currentPage) 0.95f else 0.3f)),
                        )
                    }
                }
                IconButton(
                    onClick = { actions.nav.popBackStack() },
                    modifier = Modifier.statusBarsPadding().padding(top = 18.dp, end = 4.dp).align(Alignment.TopEnd),
                ) { Icon(Icons.Rounded.Close, "Tutup", tint = Color.White) }

                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (pager.currentPage == 3) {
                        WrappedButton(Icons.Rounded.PlaylistAdd, "Simpan jadi playlist", light = false) {
                            scope.launch {
                                val songs = r.topSongs.map { it.song.toSong() }
                                val id = actions.library.createPlaylist("Lagu Teratas ${r.year}", "Dari Freease Wrapped ${r.year}")
                                actions.library.addToPlaylist(id, songs)
                                actions.toast("Playlist \"Lagu Teratas ${r.year}\" dibuat (${songs.size} lagu)")
                            }
                        }
                    }
                    WrappedButton(Icons.Rounded.Share, "Bagikan", light = true) {
                        scope.launch {
                            val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                            if (bmp == null) actions.toast("Gagal membuat gambar") else shareImage(context, bmp, "Freease Wrapped ${r.year}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, light: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (light) Color.White else Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (light) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (light) Color.Black else Color.White, fontWeight = FontWeight.Bold)
    }
}

/** Angka & teks muncul dengan animasi naik ringan saat slide dibuka. */
@Composable
private fun Rise(delayMs: Int = 0, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(700, delayMs, FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 24.dp.toPx() }) { content() }
}

@Composable
private fun Big(text: String, size: Int = 54) = Text(text, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Black, lineHeight = (size * 1.05).sp)

@Composable
private fun Small(text: String, alpha: Float = 0.85f) = Text(text, color = Color.White.copy(alpha = alpha), fontSize = 18.sp, lineHeight = 24.sp)

@Composable
private fun IntroSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    Rise { Small("FREEASE WRAPPED", 0.7f) }
    Rise(150) { Big("${r.year}", 120) }
    Spacer(Modifier.height(12.dp))
    Rise(300) { Small("Setahun penuh musik. Geser untuk melihat ceritamu →") }
}

@Composable
private fun MinutesSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    val min = r.insights.totalMin
    Rise { Small("Tahun ini kamu mendengarkan") }
    Rise(150) { Big("%,d".format(min).replace(',', '.'), 84) }
    Rise(250) { Big("menit", 40) }
    Spacer(Modifier.height(16.dp))
    Rise(400) {
        Small(
            when {
                min >= 60 * 24 -> "Itu sama dengan ${min / (60 * 24)} hari penuh tanpa henti."
                min >= 60 -> "Itu sama dengan ${min / 60} jam bersama musik."
                else -> "Awal yang manis untuk perjalanan musikmu."
            },
        )
    }
    Spacer(Modifier.height(10.dp))
    Rise(500) { Small("${r.distinctSongs} lagu berbeda dari ${r.distinctArtists} artis.") }
}

@Composable
private fun TopArtistSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    val top = r.topArtists.first()
    Rise { Small("Artis nomor satumu") }
    Spacer(Modifier.height(16.dp))
    Rise(120) {
        Artwork(Thumbs.best(top.sampleId, top.sampleThumb), Modifier.size(200.dp), shape = CircleShape)
    }
    Spacer(Modifier.height(16.dp))
    Rise(250) { Big(top.artist, 44) }
    Rise(350) { Small("${top.ms / 3_600_000} jam ${(top.ms / 60_000) % 60} menit • ${top.plays} kali diputar") }
    Spacer(Modifier.height(20.dp))
    r.topArtists.drop(1).forEachIndexed { i, a ->
        Rise(450 + i * 80) {
            Text("${i + 2}  ${a.artist}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TopSongsSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    Rise { Small("Lagu yang paling sering kamu putar") }
    Spacer(Modifier.height(18.dp))
    r.topSongs.take(5).forEachIndexed { i, st ->
        val s = st.song.toSong()
        Rise(100 + i * 100) {
            Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(34.dp))
                Artwork(s.artworkSmall, Modifier.size(58.dp), fallback = Thumbs.fallback(s.id), shape = RoundedCornerShape(6.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(s.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.artist} • ${st.plays}×", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MonthsSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    val top = r.insights.topMonth
    Rise { Small("Bulan paling bermusikmu") }
    Rise(120) { Big(MonthKey.NAMES[top], 56) }
    Rise(220) { Small("${r.insights.perMonthMin[top]} menit dalam sebulan") }
    Spacer(Modifier.height(28.dp))
    val max = (r.insights.perMonthMin.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        r.insights.perMonthMin.forEachIndexed { i, m ->
            val h = remember { Animatable(0f) }
            LaunchedEffect(Unit) { h.animateTo(m.toFloat() / max, tween(800, 300 + i * 40, FastOutSlowInEasing)) }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((h.value * 0.85f).coerceAtLeast(0.02f)).clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = if (i == top) 1f else 0.45f)),
                )
                Text(MonthKey.NAMES[i].take(1), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HabitsSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    val i = r.insights
    Rise { Small("Kebiasaan dengarmu") }
    Spacer(Modifier.height(18.dp))
    Rise(120) { Fact("%02d.00".format(i.peakHour), "jam favoritmu memutar musik") }
    Rise(240) { Fact("${i.longestStreak} hari", "berturut-turut tanpa absen") }
    Rise(360) { Fact("${i.activeDays} hari", "kamu mendengarkan musik tahun ini") }
    Rise(480) { Fact("${i.busiestDayMin} menit", "rekor dalam satu hari") }
}

@Composable
private fun Fact(value: String, label: String) = Column(Modifier.padding(vertical = 10.dp)) {
    Big(value, 40)
    Small(label, 0.8f)
}

@Composable
private fun PersonaSlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    Rise { Small("Tipe pendengarmu tahun ini") }
    Spacer(Modifier.height(10.dp))
    Rise(200) { Big(r.persona.title, 60) }
    Spacer(Modifier.height(14.dp))
    Rise(400) { Small(r.persona.blurb) }
}

@Composable
private fun SummarySlide(r: WrappedReport) = Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
    val top = r.topArtists.first()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(Thumbs.best(top.sampleId, top.sampleThumb), Modifier.size(96.dp), shape = RoundedCornerShape(10.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Small("FREEASE WRAPPED", 0.7f)
            Big("${r.year}", 40)
        }
    }
    Spacer(Modifier.height(22.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Small("Artis teratas", 0.7f)
            r.topArtists.take(5).forEachIndexed { i, a ->
                Text("${i + 1} ${a.artist}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Small("Lagu teratas", 0.7f)
            r.topSongs.take(5).forEachIndexed { i, s ->
                Text("${i + 1} ${s.song.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Small("Menit mendengarkan", 0.7f)
            Big("%,d".format(r.insights.totalMin).replace(',', '.'), 30)
        }
        Column(Modifier.weight(1f)) {
            Small("Tipe pendengar", 0.7f)
            Big(r.persona.title, 24)
        }
    }
}

private fun shareImage(context: Context, bmp: Bitmap, caption: String) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "wrapped-${System.currentTimeMillis()}.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Bagikan Wrapped").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
