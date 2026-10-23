package com.freease.music.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freease.music.FreeaseApp
import com.freease.music.playback.PlaybackService

/** Alarm berbunyi / ditunda / dimatikan, serta penjadwalan ulang setelah HP dinyalakan. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val service = Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_ALARM)
                    .putExtra(AlarmScheduler.EXTRA_ID, intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1))
                // Alarm dari setAlarmClock boleh memulai foreground service dari latar belakang.
                runCatching { ContextCompat.startForegroundService(context, service) }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> (context.applicationContext as FreeaseApp).container.alarms.rescheduleAll()
        }
    }
}
