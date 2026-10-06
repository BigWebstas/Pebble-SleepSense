package net.webstas.sleepsense

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
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
    private const val KEY_DISABLE = "disable_on_watch_off"
    private const val KEY_LAST_DISABLE = "last_disable"
    private const val TAG = "PhoneAlarmSync"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** Whether turning the Pebble alarm off also turns off the phone alarm. */
    fun isDisableOnWatchOff(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISABLE, false) // opt-in: it switches off a whole repeating alarm

    fun setDisableOnWatchOff(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DISABLE, on) }
    }

    /**
     * The Pebble alarm was turned off, so turn off the phone's next alarm too. Android has no API
     * for that, so this asks the Clock app via the standard "dismiss alarm" command. A background
     * app may only open another app's screen with the "display over other apps" permission; without
     * it this posts a notification to tap instead. Returns a one-line outcome for the log/screen.
     */
    fun disablePhoneAlarm(context: Context): String {
        if (!isEnabled(context) || !isDisableOnWatchOff(context)) return "turned off in this app's settings"
        val alarm = phoneAlarm(context) ?: return "no phone alarm to turn off"
        val label = "%02d:%02d".format(alarm.first, alarm.second)

        val clock = clockPackage(context)
        val dismiss = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clock?.let { setPackage(it) }
        }
        // Only run it unattended when it can go to exactly one known Clock app (else a chooser opens)
        return launchOrNotify(context, dismiss, clock != null, "turn off the $label alarm",
            "Pebble alarm turned off", "Tap to turn off the $label phone alarm too")
    }

    /**
     * The Pebble alarm was turned on at [hour]:[min]. Android can't re-enable an alarm (or undo a
     * skipped one), so unless the phone already has an alarm at or before that time, ask the Clock
     * app to set one. Same unattended-vs-notification rules as [disablePhoneAlarm].
     */
    fun enablePhoneAlarm(context: Context, hour: Int, min: Int): String {
        if (!isEnabled(context) || !isDisableOnWatchOff(context)) return "turned off in this app's settings"
        val label = "%02d:%02d".format(hour, min)

        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var target = now.toLocalDate().atTime(hour, min).atZone(zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        // Android occasionally reports no next alarm for a moment; look again before concluding that
        // so a glitch doesn't create a duplicate alarm.
        var next = alarmManager.nextAlarmClock
        if (next == null) {
            Thread.sleep(1500)
            next = alarmManager.nextAlarmClock
        }
        if (next != null && next.triggerTime <= target.toInstant().toEpochMilli() + 60_000) {
            return "the phone already has an alarm at or before $label"
        }

        val clock = clockPackage(context, AlarmClock.ACTION_SET_ALARM)
        val set = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, min)
            putExtra(AlarmClock.EXTRA_MESSAGE, "SleepSense")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clock?.let { setPackage(it) }
        }
        return launchOrNotify(context, set, clock != null, "set a $label alarm",
            "Pebble alarm turned on", "Tap to set the $label phone alarm too")
    }

    /** Runs a Clock command unattended when allowed, else posts a notification to tap. */
    private fun launchOrNotify(
        context: Context, command: Intent, unambiguous: Boolean, what: String, title: String, text: String,
    ): String {
        val outcome = if (Settings.canDrawOverlays(context) && unambiguous) {
            context.startActivity(command)
            "asked the Clock app to $what"
        } else {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("alarm_action", "Alarm actions", NotificationManager.IMPORTANCE_HIGH),
            )
            nm.notify(
                title.hashCode(),
                android.app.Notification.Builder(context, "alarm_action")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(PendingIntent.getActivity(context, title.hashCode(), command, PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build(),
            )
            "posted a notification to tap (needs the display-over-other-apps permission and a single Clock app to run unattended)"
        }
        Log.i(TAG, "alarm command: $outcome")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_DISABLE, "%tR: %s".format(System.currentTimeMillis(), outcome))
        }
        return outcome
    }

    /**
     * The Clock app that should receive an alarm command ([action]): the only app that handles it,
     * or the standard Clock app if there are several; null if it is ambiguous.
     */
    fun clockPackage(context: Context, action: String = AlarmClock.ACTION_DISMISS_ALARM): String? {
        val handlers = context.packageManager
            .queryIntentActivities(Intent(action), 0)
            .map { it.activityInfo.packageName }.distinct()
        return handlers.singleOrNull()
            ?: handlers.firstOrNull { it == "com.google.android.deskclock" || it == "com.android.deskclock" }
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
        val disabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_DISABLE, null)
        return "$phone\n$asked\nClock app for turning alarms off: ${clockPackage(context) ?: "unclear (will use a notification)"}" + (disabled?.let { "\nLast phone alarm turn-off: $it" } ?: "")
    }
}
