package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * The sleep history the watchapp's phone-side JavaScript pushes to the bridge (a JSON array of
 * 5-minute-bucketed sessions, see src/pkjs/lib/history.js). The newest push replaces the old one:
 * the JS holds the full history and sends all of it.
 */
object SessionStore {
    private const val FILE = "sessions.json"
    private const val PREFS = "history"
    private const val KEY_LAST_EXPORT = "last_export"

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun save(context: Context, json: String) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(json)
        tmp.renameTo(file(context)) // a reader never sees a half-written file
    }

    @Synchronized
    fun load(context: Context): String = file(context).takeIf { it.exists() }?.readText() ?: "[]"

    fun lastExport(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_EXPORT, 0)

    fun markExported(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_EXPORT, at) }
    }
}
