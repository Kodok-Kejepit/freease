package com.freease.music.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.freease.music.data.model.Thumbs
import com.freease.music.ui.components.Artwork
import com.freease.music.ui.components.darken
import com.freease.music.ui.components.rememberArtworkColor
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.theme.Freease
import kotlinx.coroutines.launch

/**
 * Mode Mobil: tombol besar yang mudah dijangkau sambil berkendara, layar tetap menyala, dan
 * pencarian suara ("putar Tulus Hati-Hati di Jalan").
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CarModeScreen() {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val song = state.song
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val liked by remember(song?.id) { song?.let { actions.library.isLiked(it.id) } ?: kotlinx.coroutines.flow.flowOf(false) }.collectAsState(false)
    val color by rememberArtworkColor(song?.artworkSmall, Freease.accent.primary.darken(0.5f))

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode != Activity.RESULT_OK || text.isNullOrBlank()) return@rememberLauncherForActivityResult
        val query = text.replace(Regex("^(putar|mainkan|play|setel)\\s+", RegexOption.IGNORE_CASE), "")
        actions.toast("Mencari \"$query\"…")
        scope.launch {
            val found = runCatching { actions.container.youtube.searchSongs(query, 5) }.getOrDefault(emptyList()).firstOrNull()
            if (found != null) actions.player.startRadio(found, "Mode Mobil") else actions.toast("Tidak menemukan \"$query\"")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(color, Color.Black)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mode Mobil", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            BigButton(Icons.Rounded.Close, "Keluar", 56.dp, Color.White.copy(alpha = 0.12f)) { actions.nav.popBackStack() }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (song != null) {
                Artwork(song.artworkUrl, Modifier.fillMaxSize(0.92f).aspectRatio(1f), fallback = Thumbs.fallback(song.id), shape = RoundedCornerShape(20.dp))
            } else {
                Text("Ketuk mikrofon lalu sebut lagu yang ingin diputar", textAlign = TextAlign.Center, fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(song?.title ?: "Freease", fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song?.artist ?: "", fontSize = 20.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BigButton(Icons.Rounded.SkipPrevious, "Sebelumnya", 84.dp) { actions.player.previous() }
            BigButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Putar/Jeda", 112.dp, Color.White, Color.Black) { actions.player.toggle() }
            BigButton(Icons.Rounded.SkipNext, "Berikutnya", 84.dp) { actions.player.next() }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Suka", 72.dp) { song?.let(actions::toggleLike) }
            BigButton(Icons.Rounded.Mic, "Cari dengan suara", 72.dp, Freease.accent.primary) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sebut judul lagu atau artis")
                runCatching { voice.launch(intent) }.onFailure { actions.toast("Pencarian suara tidak tersedia di HP ini") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BigButton(
    icon: ImageVector,
    desc: String,
    size: Dp,
    bg: Color = Color.White.copy(alpha = 0.14f),
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}
