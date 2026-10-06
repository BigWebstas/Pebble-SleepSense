package net.webstas.sleepsense

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId

/**
 * The phone's next alarm clock, offered to the SleepSense watchapp's phone-side JavaScript (see
 * AlarmBridgeService). The phone drives: the JS applies this to the watch whenever it changes.
 */
object PhoneAlarmSync {
    private const val PREFS = "alarm_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_REQUEST = "last_request"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** The next phone alarm as (hour, minute), or null if none is set. */
    fun phoneAlarm(context: Context): Pair<Int, Int>? {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val time = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour to time.minute
    }

    /** What the watchapp's JS gets: {"sync":true,"enabled":true,"hour":6,"min":45}. */
    fun json(context: Context): String {
        val alarm = phoneAlarm(context)
        return """{"sync":${isEnabled(context)},"enabled":${alarm != null},"hour":${alarm?.first ?: 0},"min":${alarm?.second ?: 0}}"""
    }

    fun noteRequest(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_REQUEST, now) }
    }

    /** Text for the app screen. */
    fun describe(context: Context): String {
        if (!isEnabled(context)) return "Alarm sync is off."
        val a = phoneAlarm(context)
        val phone = if (a != null) "Phone alarm: %02d:%02d".format(a.first, a.second)
        else "Phone alarm: none (the Pebble smart alarm will be turned off)"
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_REQUEST, 0)
        val asked = if (last == 0L) "The watch app hasn't asked yet (open SleepSense on the watch)."
        else "Watch app last asked at " + Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
            .toLocalTime().withNano(0)
        return "$phone\n$asked"
    }
}
