package com.freease.music.playback

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.freease.music.AppContainer
import com.freease.music.MainActivity
import com.freease.music.R
import com.freease.music.data.lyrics.LyricsState
import com.freease.music.data.model.Lyrics
import com.freease.music.data.model.Song
import com.freease.music.ui.player.currentLineIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Menampilkan baris lirik di luar aplikasi: gelembung melayang di atas app lain dan notifikasi
 * lirik (ikut tampil di layar kunci). Dijalankan oleh PlaybackService supaya tetap hidup saat app
 * ditutup dan memakai posisi pemutaran yang paling akurat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class LyricsDisplay(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val player: () -> Player,
) {
    /** Lagu yang sedang diputar (diisi PlaybackService). */
    val song = MutableStateFlow<Song?>(null)
    val playing = MutableStateFlow(false)

    private var lyrics: Lyrics? = null
    private var tickJob: Job? = null
    private var shownIndex = Int.MIN_VALUE
    private var overlay: View? = null
    private var lineView: TextView? = null
    private var nextView: TextView? = null
    private val nm = NotificationManagerCompat.from(context)
    private var notifShown = false
    /** Ada widget lirik di layar utama (dicek ulang tiap lagu/pengaturan berubah — murah). */
    private var widget = false

    fun start() {
        createChannel()
        scope.launch {
            song.flatMapLatest { s ->
                if (s == null) flowOf(null) else container.lyrics.state(s.id)
            }.collect { st ->
                lyrics = (st as? LyricsState.Ready)?.lyrics?.takeIf { it.canFollow }
                shownIndex = Int.MIN_VALUE
                refresh()
            }
        }
        scope.launch {
            combine(container.settings.flow, container.appVisible, playing, song) { s, visible, play, cur ->
                arrayOf(s.floatingLyrics, s.lyricsNotification, visible, play, cur)
            }.collect { a ->
                val floating = a[0] as Boolean
                val notif = a[1] as Boolean
                val visible = a[2] as Boolean
                val cur = a[4] as Song?
                widget = com.freease.music.widget.LyricsWidget.hasWidgets(context)
                // Butuh lirik → pastikan dicari (juga saat layar lirik di app tidak dibuka).
                if ((floating || notif || widget) && cur != null) container.lyrics.ensure(cur)
                val wantOverlay = floating && !visible && cur != null && canDrawOverlays()
                if (wantOverlay) showOverlay() else hideOverlay()
                if (!notif || cur == null) cancelNotification()
                shownIndex = Int.MIN_VALUE
                refresh()
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        hideOverlay()
        cancelNotification()
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(context)

    private fun active(): Boolean {
        val s = container.settings.current
        return (overlay != null) || ((s.lyricsNotification || widget) && song.value != null)
    }

    /** Ticker hanya hidup selama ada yang perlu ditampilkan dan lagu berputar. */
    private fun refresh() {
        update()
        if (active() && playing.value && lyrics != null) {
            if (tickJob?.isActive != true) {
                tickJob = scope.launch {
                    while (true) {
                        update()
                        delay(200)
                    }
                }
            }
        } else {
            tickJob?.cancel()
        }
    }

    private fun update() {
        val cur = song.value ?: return
        val l = lyrics
        val lines = l?.synced
        val idx = if (l != null) currentLineIndex(l, player().currentPosition) else -1
        if (idx == shownIndex) return
        shownIndex = idx
        val now = lines?.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: if (lines == null) cur.title else "♪"
        val next = lines?.drop(idx + 1)?.firstOrNull { it.text.isNotBlank() && it.text != "♪" }?.text
            ?: if (lines == null) cur.artist else ""
        lineView?.text = now
        nextView?.text = next
        if (widget) {
            com.freease.music.widget.LyricsWidget.push(
                context, "${cur.title} • ${cur.artist}",
                if (lines == null) "Lirik tidak tersedia" else now,
                if (lines == null) "" else next,
            )
        }
        nextView?.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        if (container.settings.current.lyricsNotification && lines != null && playing.value) {
            postNotification(cur, now, next)
        } else if (!playing.value) {
            cancelNotification()
        }
    }

    // ------------------------------------------------------------------ gelembung

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val prefs = context.getSharedPreferences("floating_lyrics", Context.MODE_PRIVATE)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        lineView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        nextView = TextView(context).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(lineView)
        texts.addView(nextView)
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12f).toInt(), dp(4f).toInt(), 0, dp(4f).toInt())
            setOnClickListener { container.settings.setFloatingLyrics(false) }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(0xE6121219.toInt())
                setStroke(dp(1f).toInt(), 0x33FFFFFF)
            }
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            addView(texts)
            addView(close)
        }
        val screenW = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenW * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (screenW * 0.07f).toInt())
            y = prefs.getInt("y", dp(120f).toInt())
        }
        // Geser untuk memindah, ketuk untuk membuka player.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    } else {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                        )
                    }
                }
            }
            true
        }
        if (runCatching { wm.addView(root, params) }.isSuccess) {
            overlay = root
            shownIndex = Int.MIN_VALUE
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(v) }
        overlay = null
        lineView = null
        nextView = null
    }

    // ------------------------------------------------------------------ notifikasi lirik

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Lirik", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Baris lirik yang sedang dinyanyikan, juga di layar kunci"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(s: Song, now: String, next: String) {
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(now)
            .setContentText(next)
            .setSubText("${s.title} · ${s.artist}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (next.isEmpty()) now else "$now\n$next"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        notifShown = true
    }

    private fun cancelNotification() {
        if (!notifShown) return
        nm.cancel(NOTIF_ID)
        notifShown = false
    }

    companion object {
        const val CHANNEL = "lyrics"
        private const val NOTIF_ID = 0x4C59
    }
}

/** Menyalakan/mematikan lirik melayang, sekalian meminta izin "tampil di atas aplikasi lain". */
object FloatingLyricsPermission {
    @UnstableApi
    fun toggle(context: Context, container: AppContainer) {
        val on = container.settings.current.floatingLyrics
        if (on) {
            container.settings.setFloatingLyrics(false)
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            container.emitMessage("Izinkan \"Tampil di atas aplikasi lain\" untuk Freease, lalu nyalakan lagi")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        container.settings.setFloatingLyrics(true)
        container.emitMessage("Lirik melayang aktif — muncul saat kamu membuka aplikasi lain")
    }
}
