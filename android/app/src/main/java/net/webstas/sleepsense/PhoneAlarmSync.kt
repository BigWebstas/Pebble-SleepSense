package net.webstas.sleepsense

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.edit
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import android.util.Log
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps the Pebble wake time in step with the phone's next alarm clock. The phone drives:
 * when its next alarm changes (or the watch reports a different time) the watch is told the new
 * time, and told to turn the smart alarm off if the phone has no alarm. Manual
 * changes made on the watch stick until the phone alarm changes again.
 */
/** Outcome of a sync attempt, worded for the app screen. */
enum class SyncResult(val message: String) {
    SENT("Sent to the watch."),
    UP_TO_DATE("Nothing to send: the phone alarm hasn't changed and the watch reports the same time."),
    DISABLED("Alarm sync is switched off."),
    WATCH_APP_CLOSED("SleepSense isn't open on the watch, so it can't receive the alarm yet."),
    WATCH_NOT_CONNECTED("No watch is connected to the phone."),
    FAILED("Couldn't reach the watch."),
}

object PhoneAlarmSync {
    private const val PREFS = "alarm_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST = "last_pushed"
    private const val KEY_WATCH = "watch_reported"
    private const val NONE = "none"
    private const val TAG = "PhoneAlarmSync"

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

    /** Remembers the alarm time the watch last reported, for display and for spotting a mismatch. */
    fun noteWatchAlarm(context: Context, hour: Int, min: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_WATCH, "%02d:%02d".format(hour, min))
        }
    }

    /**
     * Pushes the phone alarm to the watch when it changed since the last push, or when the watch
     * reports a different time ([watchHour]/[watchMin], null if unknown). [force] sends regardless
     * (the "Sync now" button). A failed push is retried on the next call.
     */
    suspend fun sync(
        context: Context,
        sender: PebbleSender,
        watchHour: Int?,
        watchMin: Int?,
        watches: List<WatchIdentifier>? = null, // null = every connected watch
        force: Boolean = false,
    ): SyncResult {
        if (!isEnabled(context)) return SyncResult.DISABLED
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val target = phoneAlarm(context)
        val key = target?.let { "%02d:%02d".format(it.first, it.second) } ?: NONE

        val changed = key != prefs.getString(KEY_LAST, null)
        val watchDiffers = target != null && watchHour != null && watchMin != null &&
            (watchHour != target.first || watchMin != target.second)
        Log.i(TAG, "sync: phone=$key last=${prefs.getString(KEY_LAST, null)} watch=$watchHour:$watchMin changed=$changed differs=$watchDiffers force=$force")
        if (!force && !changed && !watchDiffers) return SyncResult.UP_TO_DATE

        val data = buildMap<UInt, PebbleDictionaryItem> {
            if (target != null) {
                put(WatchProtocol.KEY_ALARM_TARGET_HOUR, PebbleDictionaryItem.Int32(target.first))
                put(WatchProtocol.KEY_ALARM_TARGET_MIN, PebbleDictionaryItem.Int32(target.second))
            }
            put(WatchProtocol.KEY_SMART_ALARM_ENABLED, PebbleDictionaryItem.Int32(if (target != null) 1 else 0))
        }
        val results = sender.sendDataToPebble(WatchProtocol.APP_UUID, data, watches)
        Log.i(TAG, "sync: sent $data -> $results")

        // No result at all (e.g. no watch connected) counts as a failed send and is retried later
        val values = results?.values.orEmpty()
        if (values.isNotEmpty() && values.all { it is TransmissionResult.Success }) {
            prefs.edit { putString(KEY_LAST, key) }
            return SyncResult.SENT
        }
        return when {
            values.any { it is TransmissionResult.FailedDifferentAppOpen } -> SyncResult.WATCH_APP_CLOSED
            values.isEmpty() || values.any { it is TransmissionResult.FailedWatchNotConnected } -> SyncResult.WATCH_NOT_CONNECTED
            else -> SyncResult.FAILED
        }
    }

    /**
     * Restarts SleepSense on the watch (used only for the "Sync now" button). The Pebble app
     * refuses sends until it has seen the app open, even when it is already showing, so close
     * and reopen it.
     */
    suspend fun openWatchApp(sender: PebbleSender) {
        Log.i(TAG, "stopAppOnTheWatch -> ${sender.stopAppOnTheWatch(WatchProtocol.APP_UUID, null)}")
        delay(1500)
        Log.i(TAG, "startAppOnTheWatch -> ${sender.startAppOnTheWatch(WatchProtocol.APP_UUID, null)}")
    }

    /** Text for the app screen: what the phone says, and what the watch last reported. */
    fun describe(context: Context): String {
        if (!isEnabled(context)) return "Alarm sync is off."
        val phone = phoneAlarm(context)?.let { "%02d:%02d".format(it.first, it.second) }
        val watch = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WATCH, null)
        val phoneLine = if (phone != null) "Phone alarm: $phone" else "Phone alarm: none (the Pebble smart alarm will be turned off)"
        val watchLine = when {
            watch == null -> "Watch wake time: not reported yet (open SleepSense on the watch)"
            phone != null && watch != phone -> "Watch wake time: $watch (differs, tap Sync alarm now)"
            else -> "Watch wake time: $watch"
        }
        return "$phoneLine\n$watchLine"
    }
}
