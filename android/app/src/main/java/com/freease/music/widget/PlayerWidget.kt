package com.freease.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import com.freease.music.MainActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.freease.music.R
import com.freease.music.playback.PlaybackService

/**
 * Widget layar utama: judul, artis, sampul, dan tombol sebelumnya/putar/berikutnya.
 * Tanpa pembaruan berkala — hanya digambar ulang saat service memberi kabar lagu atau status berubah.
 */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        render(context, manager, ids, p.getString("title", null), p.getString("artist", null), p.getBoolean("playing", false), null)
    }

    companion object {
        private const val PREFS = "widget_state"

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))

        /** Murah: dipakai service untuk melewatkan pekerjaan kalau tidak ada widget di layar. */
        fun hasWidgets(context: Context): Boolean = runCatching { ids(context).isNotEmpty() }.getOrDefault(false)

        fun push(context: Context, title: String?, artist: String?, playing: Boolean, art: Bitmap?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", title).putString("artist", artist).putBoolean("playing", playing).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = ids(context)
            if (ids.isNotEmpty()) render(context, manager, ids, title, artist, playing, art)
        }

        /**
         * Putar/jeda lewat MediaButtonReceiver resmi Media3: aman memulai musik saat aplikasi sudah tertutup
         * (service dinyalakan sebagai foreground service dan antrean terakhir dilanjutkan), sama seperti
         * tombol play di headset/Bluetooth. Memulai service biasa dari latar belakang diblokir Android 8+.
         */
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        private fun playPause(context: Context): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            return PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun command(context: Context, cmd: String, code: Int): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_WIDGET)
                .putExtra(PlaybackService.EXTRA_CMD, cmd)
            return PendingIntent.getService(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun render(
            context: Context, manager: AppWidgetManager, ids: IntArray,
            title: String?, artist: String?, playing: Boolean, art: Bitmap?,
        ) {
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.w_title, title ?: context.getString(R.string.widget_idle_title))
            v.setTextViewText(R.id.w_artist, artist ?: context.getString(R.string.widget_idle_subtitle))
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            if (art != null) {
                v.setViewPadding(R.id.w_art, 0, 0, 0, 0)
                v.setImageViewBitmap(R.id.w_art, art)
            }
            v.setOnClickPendingIntent(R.id.w_prev, command(context, "prev", 1))
            v.setOnClickPendingIntent(R.id.w_play, playPause(context))
            v.setOnClickPendingIntent(R.id.w_next, command(context, "next", 3))
            val open = PendingIntent.getActivity(
                context, 4,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setOnClickPendingIntent(R.id.w_art, open)
            v.setOnClickPendingIntent(R.id.w_title, open)
            manager.updateAppWidget(ids, v)
        }
    }
}
