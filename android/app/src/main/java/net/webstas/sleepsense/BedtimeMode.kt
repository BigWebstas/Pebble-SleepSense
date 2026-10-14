package net.webstas.sleepsense

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log
import androidx.core.content.edit
import io.rebble.pebblekit2.client.DefaultPebbleSender
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BedtimeMode"
private const val PREFS = "bedtime_mode"
private const val REOPEN_DELAY_MS = 3_000L
/** The watch's built-in Quiet Time Toggle app. */
private val QUIET_TIME_TOGGLE_UUID: UUID = UUID.fromString("2220d805-cf9a-4e12-92b9-5ca778aff6bb")
private const val KEY_ENABLED = "enabled"
private const val KEY_ACTIVE = "active"
private const val KEY_WATCH_QUIET = "watch_quiet"
private const val KEY_RULE_ID = "rule_id"
private const val KEY_PREVIOUS_FILTER = "previous_filter"

/**
 * Turns on the phone's Bedtime mode and the watch's Quiet Time while the watch tracks sleep, and
 * off again when tracking stops. Bedtime mode needs "Do Not Disturb access". Only undoes what it
 * did itself, so a Quiet Time or Do Not Disturb the user set up another way is left alone.
 */
object BedtimeMode {

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun hasAccess(context: Context): Boolean = manager(context).isNotificationPolicyAccessGranted

    /**
     * Follows the watch: called with every status update, acts only when tracking changes.
     * [quietTime] is the watch's own Quiet Time state, or null if it didn't say.
     */
    fun onTracking(context: Context, tracking: Boolean, quietTime: Boolean?) {
        val prefs = prefs(context)
        if (tracking == prefs.getBoolean(KEY_ACTIVE, false)) return
        prefs.edit { putBoolean(KEY_ACTIVE, tracking) }
        if (tracking) {
            if (!isEnabled(context)) return
            if (hasAccess(context)) applySafely(context, true)
            // The watch can only flip Quiet Time, so only flip it when it isn't already on
            if (quietTime == false) {
                prefs.edit { putBoolean(KEY_WATCH_QUIET, true) }
                toggleWatchQuietTime(context, reopenSleepSense = true)
            }
        } else {
            if (hasAccess(context)) applySafely(context, false)
            if (prefs.getBoolean(KEY_WATCH_QUIET, false)) {
                prefs.edit { putBoolean(KEY_WATCH_QUIET, false) }
                if (quietTime != false) toggleWatchQuietTime(context, reopenSleepSense = false)
            }
        }
    }

    /**
     * There is no way to set Quiet Time, only to flip it by launching the built-in Quiet Time
     * Toggle app. That replaces SleepSense on the watch, so while tracking it is opened again
     * (it resumes the running night).
     */
    private fun toggleWatchQuietTime(context: Context, reopenSleepSense: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            var sender: DefaultPebbleSender? = null
            try {
                sender = DefaultPebbleSender(context.applicationContext)
                Log.i(TAG, "toggle quiet time -> " + sender.startAppOnTheWatch(QUIET_TIME_TOGGLE_UUID, null))
                if (reopenSleepSense) {
                    delay(REOPEN_DELAY_MS)
                    sender.startAppOnTheWatch(WatchProtocol.APP_UUID, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not toggle quiet time: $e")
            } finally {
                runCatching { sender?.close() }
            }
        }
    }

    /** Never lets a Do Not Disturb failure take the bridge service down. */
    private fun applySafely(context: Context, on: Boolean) {
        try {
            apply(context, on)
        } catch (e: Exception) {
            Log.w(TAG, "could not turn bedtime mode ${if (on) "on" else "off"}: $e")
        }
    }

    private fun apply(context: Context, on: Boolean) {
        val nm = manager(context)
        val prefs = prefs(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val id = prefs.getString(KEY_RULE_ID, null)?.takeIf { nm.getAutomaticZenRule(it) != null }
                ?: nm.addAutomaticZenRule(bedtimeRule(context)).also { prefs.edit { putString(KEY_RULE_ID, it) } }
            val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
            nm.setAutomaticZenRuleState(id, Condition(RULE_CONDITION, "", state))
        } else if (on) {
            prefs.edit { putInt(KEY_PREVIOUS_FILTER, nm.currentInterruptionFilter) }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } else {
            nm.setInterruptionFilter(
                prefs.getInt(KEY_PREVIOUS_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL),
            )
        }
    }

    // Android reserves its own TYPE_BEDTIME rule for the Wellbeing app, so this is a rule of our own
    // (alarms only, like Bedtime mode) that shows up in Do Not Disturb as "SleepSense"
    private fun bedtimeRule(context: Context) = AutomaticZenRule(
        context.getString(R.string.app_name),
        null,
        ComponentName(context, MainActivity::class.java),
        RULE_CONDITION,
        null,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        true,
    )

    private val RULE_CONDITION: Uri = Uri.parse("sleepsense://bedtime")

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
