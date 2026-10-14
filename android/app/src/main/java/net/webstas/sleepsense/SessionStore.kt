package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The sleep history the watchapp's phone-side JavaScript pushes to the bridge (a JSON array of
 * 5-minute-bucketed sessions, see src/pkjs/lib/history.js). The newest push replaces the old one:
 * the JS holds the full history and sends all of it. A session deleted here is remembered by its
 * start time: later pushes are stripped of it, and the JS is told to drop it too (see
 * PhoneAlarmSync.json). Star ratings set here work the same way: they are laid over every push, and
 * the JS is told them too. A rating given on the watch arrives inside the pushed sessions.
 */
object SessionStore {
    private const val FILE = "sessions.json"
    private const val PREFS = "history"
    private const val KEY_LAST_EXPORT = "last_export"
    private const val KEY_DELETED = "deleted"
    private const val KEY_RATINGS = "ratings" // "start:stars" entries; 0 stars clears a rating
    private const val FORGET_AFTER_MS = 40L * 24 * 3600 * 1000 // the JS keeps 35 days, so it is long gone by then

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun save(context: Context, json: String) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(adjusted(context, json))
        tmp.renameTo(file(context)) // a reader never sees a half-written file
        CalendarSync.sync(context)
    }

    /** Ratings set here (session start to 0-5 stars), which the JS is still being told. */
    fun ratings(context: Context): Map<Long, Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_RATINGS, emptySet())!!
            .mapNotNull { entry ->
                val (start, stars) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                (start.toLongOrNull() ?: return@mapNotNull null) to (stars.toIntOrNull() ?: return@mapNotNull null)
            }.toMap()

    @Synchronized
    fun setRating(context: Context, start: Long, stars: Int, now: Long = System.currentTimeMillis()) {
        val keep = (ratings(context) + (start to stars)).filterKeys { it > now - FORGET_AFTER_MS }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_RATINGS, keep.map { (s, n) -> "$s:$n" }.toSet())
        }
        save(context, load(context))
        HomeAssistant.publishRating(context)
    }

    /** The newest finished session as {"start","end","minutes","rating"} (minutes asleep), or null if it is unrated. */
    fun latestRatedNight(context: Context): JSONObject? {
        val all = JSONArray(load(context))
        for (i in all.length() - 1 downTo 0) {
            val session = all.getJSONObject(i)
            if (session.optBoolean("open")) continue
            val rating = session.optInt("rating").takeIf { it in 1..5 } ?: return null
            return JSONObject().put("start", session.optLong("start")).put("end", session.optLong("end"))
                .put("minutes", asleepMinutes(session)).put("rating", rating)
        }
        return null
    }

    /** Minutes asleep in all finished sessions that ended today (a night and a nap add up). */
    fun asleepMinutesToday(context: Context): Long {
        val all = JSONArray(load(context))
        val today = LocalDate.now()
        var total = 0L
        for (i in 0 until all.length()) {
            val session = all.getJSONObject(i)
            if (session.optBoolean("open")) continue
            if (Instant.ofEpochMilli(session.optLong("end")).atZone(ZoneId.systemDefault()).toLocalDate() == today) {
                total += asleepMinutes(session)
            }
        }
        return total
    }

    fun asleepMinutes(session: JSONObject): Long {
        val end = session.optLong("end")
        val samples = session.optJSONArray("samples") ?: JSONArray()
        var asleepMs = 0L
        for (j in 0 until samples.length()) {
            val sample = samples.getJSONObject(j)
            if (sample.optInt("s") == 0) continue
            val until = if (j + 1 < samples.length()) samples.getJSONObject(j + 1).optLong("t") else end
            asleepMs += (until - sample.optLong("t")).coerceAtLeast(0) // a gap holds the last stage, as in report-core.js
        }
        return asleepMs / 60_000
    }

    /** Start times of sessions deleted here, which the JS is still being told to drop. */
    fun deleted(context: Context): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELETED, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    @Synchronized
    fun delete(context: Context, start: Long, now: Long = System.currentTimeMillis()) {
        val keep = (deleted(context) + start).filter { it > now - FORGET_AFTER_MS }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_DELETED, keep.map { it.toString() }.toSet())
        }
        save(context, load(context))
    }

    /** The pushed history without the sessions deleted here, and with the ratings set here. */
    private fun adjusted(context: Context, json: String): String {
        val gone = deleted(context)
        val rated = ratings(context)
        if (gone.isEmpty() && rated.isEmpty()) return json
        val all = JSONArray(json)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val session = all.getJSONObject(i)
            val start = session.optLong("start")
            if (start in gone) continue
            rated[start]?.let { session.put("rating", it) }
            kept.put(session)
        }
        return kept.toString()
    }

    @Synchronized
    fun load(context: Context): String = file(context).takeIf { it.exists() }?.readText() ?: "[]"

    fun lastExport(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_EXPORT, 0)

    fun markExported(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_EXPORT, at) }
    }
}
