package net.webstas.sleepsense

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONArray
import java.util.TimeZone

/**
 * Adds each finished night to a calendar on the phone (any account synced to the Calendar app,
 * Google included), as a "free" event so it never blocks the day. The event's id is remembered per
 * session start: a rating changed later is written to the same event, and a session deleted in the
 * app has its event removed. Runs whenever the history is saved (see SessionStore.save).
 */
object CalendarSync {
    private const val PREFS = "calendar_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CALENDAR = "calendar_id"
    private const val KEY_EVENTS = "events" // "start:eventId:stars" entries

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    class Calendar(val id: Long, val name: String)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun calendarId(context: Context): Long? = prefs(context).getLong(KEY_CALENDAR, -1).takeIf { it >= 0 }

    fun setCalendar(context: Context, id: Long) = prefs(context).edit { putLong(KEY_CALENDAR, id) }

    fun hasPermission(context: Context) = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The chosen calendar's name, or null if none is chosen or it is gone. */
    fun calendarName(context: Context): String? =
        calendarId(context)?.let { id -> calendars(context).firstOrNull { it.id == id }?.name }

    /** Calendars the app may add events to. */
    fun calendars(context: Context): List<Calendar> {
        if (!hasPermission(context)) return emptyList()
        val found = mutableListOf<Calendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            CalendarContract.Calendars.ACCOUNT_NAME,
        )?.use {
            while (it.moveToNext()) found += Calendar(it.getLong(0), "${it.getString(1)} (${it.getString(2)})")
        }
        return found
    }

    @Synchronized
    fun sync(context: Context) {
        val calendar = calendarId(context)
        if (!isEnabled(context) || calendar == null || !hasPermission(context)) return
        try {
            val synced = events(context)
            val gone = SessionStore.deleted(context)
            for ((start, entry) in synced.toMap()) {
                if (start in gone) {
                    context.contentResolver.delete(eventUri(entry.first), null, null)
                    synced.remove(start)
                }
            }
            val all = JSONArray(SessionStore.load(context))
            for (i in 0 until all.length()) {
                val session = all.getJSONObject(i)
                if (session.optBoolean("open")) continue
                val start = session.optLong("start")
                val end = session.optLong("end")
                val stars = session.optInt("rating")
                val asleep = SessionStore.asleepMinutes(session)
                if (asleep == 0L) continue // nothing slept: a test run or a mis-start
                val known = synced[start]
                if (known != null && known.second == stars) continue
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, "Sleep · ${duration(asleep)}")
                    put(CalendarContract.Events.DESCRIPTION, if (stars in 1..5) "Rated $stars/5" else "")
                }
                if (known != null) {
                    context.contentResolver.update(eventUri(known.first), values, null, null)
                    synced[start] = known.first to stars
                } else {
                    values.put(CalendarContract.Events.CALENDAR_ID, calendar)
                    values.put(CalendarContract.Events.DTSTART, start)
                    values.put(CalendarContract.Events.DTEND, end)
                    values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    values.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: continue
                    synced[start] = ContentUris.parseId(uri) to stars
                }
            }
            save(context, synced)
        } catch (e: SecurityException) {
            // permission revoked mid-sync: nothing more to do until it is granted again
        }
    }

    private fun duration(minutes: Long) = "${minutes / 60}h ${minutes % 60}m"

    private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    private fun events(context: Context): MutableMap<Long, Pair<Long, Int>> =
        prefs(context).getStringSet(KEY_EVENTS, emptySet())!!.mapNotNull { entry ->
            val p = entry.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
            (p[0].toLongOrNull() ?: return@mapNotNull null) to
                ((p[1].toLongOrNull() ?: return@mapNotNull null) to (p[2].toIntOrNull() ?: return@mapNotNull null))
        }.toMap().toMutableMap()

    private fun save(context: Context, events: Map<Long, Pair<Long, Int>>) =
        prefs(context).edit { putStringSet(KEY_EVENTS, events.map { (s, e) -> "$s:${e.first}:${e.second}" }.toSet()) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
