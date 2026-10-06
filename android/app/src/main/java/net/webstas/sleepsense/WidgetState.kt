package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId

/**
 * What the home-screen widget shows, fed by the watchapp's phone-side JavaScript: it reports
 * whether the watch is tracking with every minute check-in (and right away when that changes), so
 * "no check-in lately" means the watch app is closed.
 */
object WidgetState {
    private const val PREFS = "widget_state"
    private const val ALIVE_MS = 150_000L      // check-ins come every 60 s
    private const val STARTING_MS = 60_000L    // how long "Starting..." is shown after a tap

    fun note(context: Context, tracking: Boolean, since: Long, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("tracking", tracking)
            putLong("since", since)
            putLong("seen", now)
            if (tracking) remove("starting")
        }
    }

    fun markStarting(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("starting", now) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun watchAppOpen(context: Context, now: Long = System.currentTimeMillis()) =
        now - prefs(context).getLong("seen", 0) < ALIVE_MS

    fun tracking(context: Context, now: Long = System.currentTimeMillis()) =
        watchAppOpen(context, now) && prefs(context).getBoolean("tracking", false)

    private fun starting(context: Context, now: Long) = now - prefs(context).getLong("starting", 0) < STARTING_MS

    private fun clock(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    /** The two lines the widget shows. */
    fun lines(context: Context, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val alarm = PhoneAlarmSync.phoneAlarm(context)?.let { "Alarm %02d:%02d".format(it.first, it.second) } ?: "No alarm"
        val since = prefs(context).getLong("since", 0)
        return when {
            tracking(context, now) -> ("Tracking sleep" + if (since > 0) " since ${clock(since)}" else "") to alarm
            starting(context, now) -> "Starting..." to alarm
            watchAppOpen(context, now) -> "Not tracking" to "Tap to start · $alarm"
            else -> "Watch app closed" to "Tap to open and start · $alarm"
        }
    }
}

/** A "start tracking" request from the widget, handed to the watchapp's JS when it asks. */
object Commands {
    private val lock = Object()
    private var pendingStart = false
    private var newestPoll = 0

    fun queueStart() = synchronized(lock) {
        pendingStart = true
        lock.notifyAll()
    }

    /**
     * Holds the caller up to [timeoutMs] until a command is queued; returns "start" or "none". Only
     * the newest poll can take a command: an older one (its client gone, e.g. the watch app closed)
     * gives up as soon as a newer poll arrives, so it can't swallow a command.
     */
    fun await(timeoutMs: Long): String = synchronized(lock) {
        val mine = ++newestPoll
        lock.notifyAll()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!pendingStart) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0 || newestPoll != mine) return "none"
            lock.wait(left)
        }
        if (newestPoll != mine) return "none"
        pendingStart = false
        "start"
    }
}
