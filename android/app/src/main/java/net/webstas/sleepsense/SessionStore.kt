package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.io.File

/**
 * The sleep history the watchapp's phone-side JavaScript pushes to the bridge (a JSON array of
 * 5-minute-bucketed sessions, see src/pkjs/lib/history.js). The newest push replaces the old one:
 * the JS holds the full history and sends all of it. A session deleted here is remembered by its
 * start time: later pushes are stripped of it, and the JS is told to drop it too (see
 * PhoneAlarmSync.json).
 */
object SessionStore {
    private const val FILE = "sessions.json"
    private const val PREFS = "history"
    private const val KEY_LAST_EXPORT = "last_export"
    private const val KEY_DELETED = "deleted"
    private const val FORGET_AFTER_MS = 40L * 24 * 3600 * 1000 // the JS keeps 35 days, so it is long gone by then

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun save(context: Context, json: String) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(withoutDeleted(context, json))
        tmp.renameTo(file(context)) // a reader never sees a half-written file
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

    private fun withoutDeleted(context: Context, json: String): String {
        val gone = deleted(context)
        if (gone.isEmpty()) return json
        val all = JSONArray(json)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val session = all.getJSONObject(i)
            if (session.optLong("start") !in gone) kept.put(session)
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
