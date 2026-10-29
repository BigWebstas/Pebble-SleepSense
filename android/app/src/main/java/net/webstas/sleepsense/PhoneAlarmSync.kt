package net.webstas.sleepsense

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId

/**
 * The phone's next alarm clock, offered to the SleepSense watchapp's phone-side JavaScript (see
 * AlarmBridgeService). The phone drives: the JS applies this to the watch whenever it changes,
 * including turning the watch alarm off when the phone has no alarm. Nothing here ever changes
 * the phone's alarms.
 */
object PhoneAlarmSync {
    private const val PREFS = "alarm_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALARM_ENABLED = "alarm_enabled"
    private const val KEY_LAST_REQUEST = "last_request"
    private const val KEY_REVISION = "revision"
    private const val KEY_WINDOW = "smart_window_minutes" // reported by the watch, never sent to it
    private const val KEY_CUSTOM = "custom_alarm" // minutes after midnight; unset until chosen
    private const val KEY_SNOOZE = "snooze_minutes" // unset until chosen, so the watch's own value stands

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
        noteAlarmChanged(context) // the alarm the watch should follow may now be a different one
    }

    fun isAlarmEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALARM_ENABLED, true)

    fun setAlarmEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ALARM_ENABLED, enabled) }
        noteAlarmChanged(context)
        Commands.queueSync()
    }

    /** The smart alarm window set on the watch (minutes), or null until the watch has reported it. */
    fun smartWindowMinutes(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WINDOW, -1).takeIf { it > 0 }

    fun setSmartWindowMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_WINDOW, minutes) }
    }

    /** The alarm was switched on or off on the watch: follow it, without sending it back. */
    fun alarmEnabledOnWatch(context: Context, enabled: Boolean) {
        if (enabled == isAlarmEnabled(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ALARM_ENABLED, enabled) }
        HomeAssistant.publishAlarmEnabled(context)
    }

    /**
     * The alarm time was changed on the watch: it becomes the alarm chosen in this app. Syncing the
     * phone's alarm is turned off, as it would put the phone's time back on the watch within a minute.
     * Followed without sending it back, like [alarmEnabledOnWatch].
     */
    fun alarmTimeOnWatch(context: Context, hour: Int, minute: Int) {
        if (!isEnabled(context) && customAlarm(context) == hour to minute) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, false)
            putInt(KEY_CUSTOM, hour * 60 + minute)
        }
        HomeAssistant.publishAlarmTime(context)
    }

    /** The alarm for the watch as (hour, minute), or null if none: the phone's next alarm while syncing, else the one chosen in the app. */
    fun phoneAlarm(context: Context): Pair<Int, Int>? {
        if (!isEnabled(context)) return customAlarm(context)
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        val time = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour to time.minute
    }

    /** The alarm time chosen in this app (used when syncing the phone's alarm is off), or null if never chosen. */
    fun customAlarm(context: Context): Pair<Int, Int>? {
        val minutes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CUSTOM, -1)
        return if (minutes < 0) null else minutes / 60 to minutes % 60
    }

    fun setCustomAlarm(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_CUSTOM, hour * 60 + minute) }
        noteAlarmChanged(context)
    }

    /** Snooze length chosen in this app (0 = snooze off), or null if never chosen. */
    fun snoozeMinutes(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SNOOZE, -1).takeIf { it >= 0 }
            ?.let { if (it == 9) 10 else it } // 9 minutes is no longer offered

    fun setSnoozeMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_SNOOZE, minutes) }
    }

    /** Counts every change Android reports to the next alarm, so a quick off/on is never missed. */
    fun noteAlarmChanged(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0) + 1) }
        HomeAssistant.publishAlarmTime(context)
        HomeAssistant.publishAlarmEnabled(context)
    }

    /**
     * What the watchapp's JS gets: {"sync":true,"rev":3,"enabled":true,"hour":6,"min":45,"snooze":9}.
     * "sync" is false when there is no alarm to apply (syncing is off and none was chosen here);
     * "snooze" is left out until a length was chosen here. "deleted" lists start times of history
     * sessions deleted in the app, for the JS to drop from its own copy; "ratings" maps session start
     * times to the stars set in the app, for the JS to take over. Both are left out when empty.
     */
    fun json(context: Context): String {
        val alarm = phoneAlarm(context)
        val rev = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_REVISION, 0)
        val enabled = isAlarmEnabled(context) && alarm != null
        val sync = isEnabled(context) || alarm != null
        val snooze = snoozeMinutes(context)?.let { ""","snooze":$it""" } ?: ""
        val deleted = SessionStore.deleted(context).takeIf { it.isNotEmpty() }?.let { ""","deleted":[${it.joinToString(",")}]""" } ?: ""
        val ratings = SessionStore.ratings(context).takeIf { it.isNotEmpty() }
            ?.let { ""","ratings":{${it.entries.joinToString(",") { (start, stars) -> "\"$start\":$stars" }}}""" } ?: ""
        return """{"sync":$sync,"rev":$rev,"enabled":$enabled,"hour":${alarm?.first ?: 0},"min":${alarm?.second ?: 0}$snooze$deleted$ratings}"""
    }

    fun noteRequest(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_REQUEST, now) }
    }

    /** Text for the app screen. */
    fun describe(context: Context): String {
        if (!isAlarmEnabled(context)) return "Watch alarm is disabled."
        if (!isEnabled(context)) {
            val custom = customAlarm(context)
            return if (custom != null) "Watch alarm: %02d:%02d".format(custom.first, custom.second)
            else "Alarm sync is off."
        }
        val a = phoneAlarm(context)
        val phone = if (a != null) "Phone alarm: %02d:%02d".format(a.first, a.second)
        else "Phone alarm: none (the Pebble smart alarm is turned off)"
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_REQUEST, 0)
        val asked = if (last == 0L) "The watch app hasn't asked yet (open SleepSense on the watch)."
        else "Watch app last asked at " + Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
            .toLocalTime().withNano(0)
        return "$phone\n$asked"
    }
}
