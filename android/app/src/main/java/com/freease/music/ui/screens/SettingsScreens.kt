package com.freease.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.freease.music.BuildConfig
import com.freease.music.data.prefs.AudioQuality
import com.freease.music.playback.EqInfo
import com.freease.music.ui.components.ConfirmDialog
import com.freease.music.ui.components.FilterChipRow
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.Routes
import com.freease.music.ui.theme.Accents
import com.freease.music.ui.theme.Freease
import com.freease.music.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = Freease.accent.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
private fun Item(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Item(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Freease.accent.primary, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Freease.colors.elevated,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(v); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v == selected, onClick = { onSelect(v); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = Freease.accent.primary))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Freease.colors.muted) } },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SettingsScreen() {
    val actions = LocalActions.current
    val c = actions.container
    val s by c.settings.flow.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { runCatching { c.mediaCache.cacheSpace }.getOrDefault(0L) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item { SettingsTopBar("Pengaturan") { actions.nav.popBackStack() } }

        item { Group("Kualitas suara") }
        item { Item("Kualitas streaming", "${s.audioQuality.label} — ${s.audioQuality.desc}", onClick = { dialog = "quality" }) }
        item { SwitchItem("Hemat data di jaringan seluler", "Pakai kualitas hemat saat tidak di Wi-Fi", s.saverOnCellular, c.settings::setSaverOnCellular) }
        item { Item("Kualitas video", "Maksimal ${s.videoQuality}p", onClick = { dialog = "video" }) }
        item {
            Item("Equalizer & efek suara", if (s.eqEnabled) "Aktif" else "Nonaktif", onClick = { actions.navigate(Routes.EQUALIZER) }) {
                Icon(Icons.Rounded.ChevronRight, null, tint = Freease.colors.muted)
            }
        }

        item { Group("Pemutaran") }
        item { SwitchItem("Fade halus saat putar/jeda", "Tanpa bunyi \"klik\" saat play atau pause", s.fadePlayPause, c.settings::setFadePlayPause) }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Crossfade antarlagu", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (s.crossfadeSec == 0) "Mati — perpindahan gapless" else "${s.crossfadeSec} detik",
                    style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted,
                )
                var v by remember(s.crossfadeSec) { mutableFloatStateOf(s.crossfadeSec.toFloat()) }
                Slider(
                    value = v, onValueChange = { v = it }, valueRange = 0f..12f, steps = 11,
                    onValueChangeFinished = { c.settings.setCrossfade(v.toInt()) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Freease.accent.primary),
                )
            }
        }
        item { SwitchItem("Lewati bagian hening", "Potong jeda sunyi di awal/akhir lagu", s.skipSilence, c.settings::setSkipSilence) }
        item { SwitchItem("Putar otomatis", "Lanjutkan dengan lagu serupa saat antrean habis", s.autoplay, c.settings::setAutoplay) }
        item { SwitchItem("Lanjutkan antrean terakhir", "Saat aplikasi dibuka lagi", s.resumeOnStart, c.settings::setResumeOnStart) }
        item { SwitchItem("Kartu lirik di player", null, s.showLyricsCard, c.settings::setShowLyricsCard) }

        item { Group("Tampilan") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Warna aksen", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(Accents) { i, a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(a.brush)
                                    .border(2.dp, if (i == s.accent) Color.White else Color.Transparent, CircleShape)
                                    .clickable { c.settings.setAccent(i) },
                                contentAlignment = Alignment.Center,
                            ) { if (i == s.accent) Icon(Icons.Rounded.Check, null, tint = Color.White) }
                            Spacer(Modifier.height(4.dp))
                            Text(a.name, style = MaterialTheme.typography.labelSmall, color = Freease.colors.muted)
                        }
                    }
                }
            }
        }
        item { SwitchItem("Warna dinamis dari sampul", "Latar player mengikuti warna artwork", s.dynamicColor, c.settings::setDynamicColor) }
        item { SwitchItem("Mode AMOLED (hitam pekat)", "Lebih hemat baterai di layar OLED", s.amoled, c.settings::setAmoled) }

        item { Group("Konten") }
        item { Item("Wilayah konten", "Trending & rekomendasi: ${s.region} (berlaku setelah app dibuka ulang)", onClick = { dialog = "region" }) }

        item { Group("Penyimpanan") }
        item { SwitchItem("Unduh hanya lewat Wi-Fi", null, s.downloadWifiOnly, c.settings::setDownloadWifiOnly) }
        item {
            Item(
                "Batas cache streaming",
                (if (s.cacheSizeMb <= 0) "Tanpa batas" else Format.bytes(s.cacheSizeMb * 1024L * 1024L)) +
                    " • terpakai ${if (cacheBytes >= 0) Format.bytes(cacheBytes) else "…"} (berlaku setelah app dibuka ulang)",
                onClick = { dialog = "cache" },
            )
        }
        item {
            Item("Bersihkan cache streaming", "Lagu yang belum diunduh perlu dimuat ulang", onClick = { dialog = "clearCache" })
        }
        item { Item("Hapus riwayat pencarian", onClick = { scope.launch { actions.library.clearSearches(); actions.toast("Riwayat pencarian dihapus") } }) }
        item { Item("Hapus riwayat & statistik dengar", onClick = { dialog = "clearHistory" }) }

        item { Group("Tentang") }
        item { Item("Freease ${BuildConfig.VERSION_NAME}", "Pemutar musik pribadi tanpa iklan • by Kodok-Kejepit") }
        item { Item("Sumber data", "YouTube & YouTube Music (NewPipeExtractor) • Lirik: LRCLIB") }
    }

    when (dialog) {
        "quality" -> ChoiceDialog(
            "Kualitas streaming",
            AudioQuality.entries.map { it to "${it.label} — ${it.desc}" },
            s.audioQuality, { dialog = null }, c.settings::setAudioQuality,
        )
        "video" -> ChoiceDialog(
            "Kualitas video maksimal",
            listOf(1080, 720, 480, 360, 240).map { it to "${it}p" },
            s.videoQuality, { dialog = null }, c.settings::setVideoQuality,
        )
        "cache" -> ChoiceDialog(
            "Batas cache",
            listOf(256 to "256 MB", 512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 0 to "Tanpa batas"),
            s.cacheSizeMb, { dialog = null }, c.settings::setCacheSize,
        )
        "region" -> ChoiceDialog(
            "Wilayah konten",
            listOf("ID" to "Indonesia", "MY" to "Malaysia", "SG" to "Singapura", "US" to "Amerika Serikat", "GB" to "Inggris", "JP" to "Jepang", "KR" to "Korea Selatan"),
            s.region, { dialog = null }, c.settings::setRegion,
        )
        "clearCache" -> ConfirmDialog("Bersihkan cache?", "Lagu unduhan tidak ikut terhapus.", "Bersihkan", { dialog = null }) {
            c.appScope.launch {
                val cache = c.mediaCache
                cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
                cacheBytes = cache.cacheSpace
                actions.toast("Cache dibersihkan")
            }
        }
        "clearHistory" -> ConfirmDialog("Hapus riwayat?", "Riwayat putar dan statistik akan dikosongkan.", "Hapus", { dialog = null }) {
            c.appScope.launch { actions.library.clearHistory() }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EqualizerScreen() {
    val actions = LocalActions.current
    val c = actions.container
    val s by c.settings.flow.collectAsState()
    var info by remember { mutableStateOf<EqInfo?>(c.effects.info.value) }
    LaunchedEffect(Unit) { if (info == null) info = withContext(Dispatchers.Default) { c.effects.queryInfo() } }
    val eq = info
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Freease.accent.primary)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
        item { SettingsTopBar("Equalizer") { actions.nav.popBackStack() } }
        item { SwitchItem("Aktifkan efek suara", "Equalizer, bass boost, 3D, dan penguat volume", s.eqEnabled, c.settings::setEqEnabled) }
        if (eq == null) {
            item {
                Text(
                    "Perangkat ini tidak menyediakan equalizer bawaan.",
                    color = Freease.colors.muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@LazyColumn
        }
        item { Group("Preset") }
        item {
            val names = listOf("Kustom") + eq.presets
            FilterChipRow(names, s.eqPreset + 1, { i ->
                if (i == 0) {
                    c.settings.setEq(-1, s.eqBands.ifEmpty { List(eq.bandsHz.size) { 0 } })
                } else {
                    val levels = c.effects.presetLevels(i - 1)
                    c.settings.setEq(i - 1, levels)
                    if (!s.eqEnabled) c.settings.setEqEnabled(true)
                }
            })
        }
        item { Group("Band") }
        val bands = s.eqBands.takeIf { it.size == eq.bandsHz.size } ?: List(eq.bandsHz.size) { 0 }
        eq.bandsHz.forEachIndexed { i, hz ->
            item(key = "band_$i") {
                var v by remember(bands[i]) { mutableFloatStateOf(bands[i].toFloat()) }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                    Row {
                        Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%+.1f dB", v / 100f), style = MaterialTheme.typography.labelMedium, color = Freease.colors.muted)
                    }
                    Slider(
                        value = v,
                        onValueChange = { v = it },
                        valueRange = eq.minLevel.toFloat()..eq.maxLevel.toFloat(),
                        enabled = s.eqEnabled,
                        colors = sliderColors,
                        onValueChangeFinished = {
                            val updated = bands.toMutableList().also { it[i] = v.toInt() }
                            c.settings.setEq(-1, updated)
                        },
                    )
                }
            }
        }
        item { Group("Efek") }
        item {
            EffectSlider("Bass boost", s.bassBoost, 1000, s.eqEnabled, c.settings::setBassBoost) { "${it / 10}%" }
            EffectSlider("Virtualizer 3D", s.virtualizer, 1000, s.eqEnabled, c.settings::setVirtualizer) { "${it / 10}%" }
            EffectSlider("Penguat volume", s.loudnessGain, 1500, s.eqEnabled, c.settings::setLoudness) {
                String.format(Locale.US, "+%.1f dB", it / 100f)
            }
            Text(
                "Tips: penguat volume membantu lagu yang direkam pelan; naikkan perlahan agar suara tidak pecah.",
                style = MaterialTheme.typography.bodySmall,
                color = Freease.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EffectSlider(label: String, value: Int, max: Int, enabled: Boolean, onSet: (Int) -> Unit, format: (Int) -> String) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(v.toInt()), style = MaterialTheme.typography.labelMedium, color = Freease.colors.muted)
        }
        Slider(
            value = v, onValueChange = { v = it }, valueRange = 0f..max.toFloat(), enabled = enabled,
            onValueChangeFinished = { onSet(v.toInt()) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Freease.accent.primary),
        )
    }
}
