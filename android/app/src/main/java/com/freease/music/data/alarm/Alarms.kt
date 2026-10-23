package com.freease.music.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.freease.music.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Alarm musik. [days] berisi Calendar.SUNDAY..SATURDAY; kosong = sekali saja.
 * [source]: "liked", "recent", "downloads", "local", atau "pl:<id playlist>".
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val source: String = "liked",
    val sourceLabel: String = "Lagu yang Disukai",
    val fadeSec: Int = 60,
    val shuffle: Boolean = true,
    val enabled: Boolean = true,
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    val daysText: String get() = when {
        days.isEmpty() -> "Sekali"
        days.size == 7 -> "Setiap hari"
        days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "Hari kerja"
        days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "Akhir pekan"
        else -> DAY_ORDER.filter { it in days }.joinToString(", ") { DAY_SHORT.getValue(it) }
    }

    companion object {
        val DAY_ORDER = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val DAY_SHORT = mapOf(
            Calendar.MONDAY to "Sen", Calendar.TUESDAY to "Sel", Calendar.WEDNESDAY to "Rab", Calendar.THURSDAY to "Kam",
            Calendar.FRIDAY to "Jum", Calendar.SATURDAY to "Sab", Calendar.SUNDAY to "Min",
        )

        /** Waktu bunyi berikutnya (epoch ms) setelah [now]. */
        fun nextTrigger(a: Alarm, now: Long, cal: Calendar = Calendar.getInstance()): Long {
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, a.hour)
            cal.set(Calendar.MINUTE, a.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            for (i in 0..7) {
                if (i > 0) cal.add(Calendar.DAY_OF_YEAR, 1)
                if (cal.timeInMillis <= now) continue
                if (a.days.isEmpty() || cal.get(Calendar.DAY_OF_WEEK) in a.days) return cal.timeInMillis
            }
            return cal.timeInMillis
        }
    }
}

/** Daftar alarm, disimpan di SharedPreferences sebagai JSON. */
class AlarmStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val _alarms = MutableStateFlow(load())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private fun load(): List<Alarm> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val days = o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) }.toSet() } ?: emptySet()
            Alarm(
                o.getInt("id"), o.getInt("h"), o.getInt("m"), days,
                o.optString("src", "liked"), o.optString("srcLabel", "Lagu yang Disukai"),
                o.optInt("fade", 60), o.optBoolean("shuffle", true), o.optBoolean("on", true),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute)
                    .put("days", JSONArray(a.days.toList())).put("src", a.source).put("srcLabel", a.sourceLabel)
                    .put("fade", a.fadeSec).put("shuffle", a.shuffle).put("on", a.enabled),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        _alarms.value = list
        AlarmScheduler.scheduleAll(context, list)
    }

    fun get(id: Int) = _alarms.value.firstOrNull { it.id == id }

    fun upsert(a: Alarm) {
        val list = _alarms.value.filter { it.id != a.id } + a
        save(list.sortedWith(compareBy({ it.hour }, { it.minute })))
    }

    fun delete(id: Int) = save(_alarms.value.filter { it.id != id })

    fun newId(): Int = (_alarms.value.maxOfOrNull { it.id } ?: 100) + 1

    /** Alarm sekali-bunyi dimatikan setelah berbunyi. */
    fun fired(id: Int) {
        val a = get(id) ?: return
        if (a.days.isEmpty()) upsert(a.copy(enabled = false)) else AlarmScheduler.scheduleAll(context, _alarms.value)
    }

    fun rescheduleAll() = AlarmScheduler.scheduleAll(context, _alarms.value)
}

object AlarmScheduler {
    const val ACTION_FIRE = "freease.ALARM_FIRE"
    const val ACTION_SNOOZE = "freease.ALARM_SNOOZE"
    const val ACTION_DISMISS = "freease.ALARM_DISMISS"
    const val EXTRA_ID = "alarm_id"
    const val SNOOZE_ID_OFFSET = 50_000
    const val SNOOZE_MIN = 10

    private fun fireIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0x41,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALARMS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleAll(context: Context, list: List<Alarm>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        list.forEach { a ->
            val pi = fireIntent(context, a.id)
            if (!a.enabled) {
                am.cancel(pi)
                return@forEach
            }
            schedule(context, am, Alarm.nextTrigger(a, now), pi)
        }
    }

    /** Tunda: bunyikan alarm yang sama lagi [SNOOZE_MIN] menit lagi. */
    fun snooze(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
        schedule(context, am, at, fireIntent(context, id + SNOOZE_ID_OFFSET))
    }

    private fun schedule(context: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(context)), pi)
        } else {
            // Izin alarm tepat dicabut: tetap dijadwalkan, mungkin meleset beberapa menit.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
