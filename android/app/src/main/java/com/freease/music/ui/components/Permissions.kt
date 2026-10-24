package com.freease.music.ui.components

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.freease.music.ui.theme.Freease

/** Satu izin yang dipakai Freease. [runtime] = izin dialog Android; null = diatur lewat halaman Pengaturan sistem. */
class AppPermission(
    val id: String,
    val title: String,
    val reason: String,
    val icon: ImageVector,
    val runtime: String?,
    val settingsIntent: (Context) -> Intent,
    val granted: (Context) -> Boolean,
)

object AppPermissions {
    private fun appDetails(c: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Izin yang relevan untuk versi Android ini. */
    fun all(): List<AppPermission> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                AppPermission(
                    "notif", "Notifikasi", "Kontrol musik di notifikasi & layar kunci, alarm musik, dan lirik di layar kunci.",
                    Icons.Rounded.Notifications, Manifest.permission.POST_NOTIFICATIONS,
                    { c ->
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                    },
                    { c -> has(c, Manifest.permission.POST_NOTIFICATIONS) },
                ),
            )
        }
        add(
            AppPermission(
                "mic", "Mikrofon", "Untuk Kenali Lagu. Hanya aktif saat tombolnya ditekan, rekaman tidak disimpan.",
                Icons.Rounded.Mic, Manifest.permission.RECORD_AUDIO, ::appDetails,
                { c -> has(c, Manifest.permission.RECORD_AUDIO) },
            ),
        )
        add(
            AppPermission(
                "audio", "Musik & audio", "Untuk memutar lagu MP3/FLAC/M4A yang tersimpan di HP.",
                Icons.Rounded.LibraryMusic, audioPermission, ::appDetails,
                { c -> has(c, audioPermission) },
            ),
        )
        add(
            AppPermission(
                "overlay", "Tampil di atas aplikasi lain", "Untuk Lirik Melayang saat kamu membuka aplikasi lain.",
                Icons.Rounded.Layers, null,
                { c -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")) },
                { c -> Settings.canDrawOverlays(c) },
            ),
        )
        if (Build.VERSION.SDK_INT >= 31) {
            add(
                AppPermission(
                    "alarm", "Alarm & pengingat", "Supaya Alarm Musik berbunyi tepat waktu.",
                    Icons.Rounded.Alarm, null,
                    { c -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}")) },
                    { c -> c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false },
                ),
            )
        }
        add(
            AppPermission(
                "battery", "Tanpa batasan baterai", "Supaya musik & alarm tidak dihentikan sistem saat layar mati (disarankan untuk HP Xiaomi, Oppo, vivo, dll.).",
                Icons.Rounded.BatteryChargingFull, null,
                { c ->
                    @android.annotation.SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
                    i
                },
                { c -> c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) != false },
            ),
        )
    }

    fun missing(c: Context): List<AppPermission> = all().filterNot { runCatching { it.granted(c) }.getOrDefault(true) }

    // ---- kapan pengingat otomatis muncul

    private const val PREFS = "permission_prompt"
    private const val SNOOZE_MS = 3L * 24 * 3600_000

    /** Muncul otomatis saat app dibuka: hanya kalau ada izin yang belum aktif dan belum ditunda (3 hari), kecuali ada yang baru. */
    fun shouldPrompt(c: Context): Boolean {
        val missing = missing(c).map { it.id }.toSet()
        if (missing.isEmpty()) return false
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snoozed = p.getStringSet("ids", emptySet()).orEmpty()
        val at = p.getLong("at", 0)
        return (missing - snoozed).isNotEmpty() || System.currentTimeMillis() - at > SNOOZE_MS
    }

    fun snooze(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("ids", missing(c).map { it.id }.toSet())
            .putLong("at", System.currentTimeMillis())
            .apply()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Lembar "Izin yang belum aktif": hanya menampilkan izin yang belum diberikan, diperbarui saat kembali
 * dari halaman Pengaturan, dan tertutup sendiri begitu semuanya aktif. [showAll] menampilkan semua izin
 * beserta statusnya (dari menu Pengaturan).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(showAll: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val items = remember(tick) { AppPermissions.all().map { it to runCatching { it.granted(context) }.getOrDefault(true) } }
    val missing = items.filter { !it.second }
    var pending by remember { mutableStateOf<AppPermission?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending
        pending = null
        tick++
        if (!ok && p != null) {
            // Ditolak permanen ("Jangan tanya lagi"): arahkan ke halaman Pengaturan aplikasi.
            val activity = context.findActivity()
            val canAsk = activity != null && p.runtime != null && activity.shouldShowRequestPermissionRationale(p.runtime)
            if (!canAsk) runCatching { context.startActivity(p.settingsIntent(context)) }
        }
    }

    if (!showAll && missing.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Freease.colors.elevated) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                if (missing.isEmpty()) "Semua izin sudah aktif" else "Izin yang belum aktif",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Text(
                if (missing.isEmpty()) "Semua fitur Freease bisa dipakai sepenuhnya."
                else "Beberapa fitur butuh izin berikut. Izin yang sudah aktif tidak ditampilkan lagi.",
                style = MaterialTheme.typography.bodyMedium, color = Freease.colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            (if (showAll) items else missing).forEach { (p, ok) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Freease.colors.card)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (ok) Color(0x332ECC71) else Freease.accent.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(p.icon, null, tint = if (ok) Color(0xFF2ECC71) else Freease.accent.primary) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = Freease.colors.muted)
                    }
                    if (ok) {
                        Icon(Icons.Rounded.CheckCircle, "Aktif", tint = Color(0xFF2ECC71))
                    } else {
                        Box(
                            Modifier.clip(CircleShape).background(Freease.accent.primary).clickable {
                                if (p.runtime != null) {
                                    pending = p
                                    launcher.launch(p.runtime)
                                } else {
                                    runCatching { context.startActivity(p.settingsIntent(context)) }
                                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text("Izinkan", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (!showAll) AppPermissions.snooze(context)
                    onDismiss()
                }) { Text(if (missing.isEmpty() || showAll) "Tutup" else "Nanti saja", color = Freease.colors.muted) }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
