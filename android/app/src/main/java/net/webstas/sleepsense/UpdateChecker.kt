package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** What looking for a new version found. */
sealed class UpdateResult {
    data class UpToDate(val version: String) : UpdateResult()
    data class Available(val version: String, val pageUrl: String) : UpdateResult()
    object NoReleases : UpdateResult()
    object Failed : UpdateResult()
}

/**
 * Looks at the latest GitHub release of this project and compares its tag (for example "v0.2.0")
 * with the installed version. It only reports; installing is left to you through the release page.
 */
object UpdateChecker {
    const val REPO = "BigWebstas/PebbleSleepTracker"
    private const val PREFS = "update"
    private const val CHECK_EVERY_MS = 12 * 3600_000L

    /** True when the quiet check on app start is due (at most twice a day). */
    fun dueForAutoCheck(context: Context, now: Long = System.currentTimeMillis()) =
        now - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_check", 0) > CHECK_EVERY_MS

    /** Blocking network call: run it off the main thread. */
    fun check(context: Context, installed: String): UpdateResult {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("last_check", System.currentTimeMillis()) }
        return try {
            val connection = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode == 404) return UpdateResult.NoReleases // no repo or no release yet
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val latest = json.getString("tag_name")
                if (isNewer(latest, installed)) UpdateResult.Available(latest, json.getString("html_url"))
                else UpdateResult.UpToDate(installed)
            } finally {
                connection.disconnect()
            }
        } catch (e: FileNotFoundException) {
            UpdateResult.NoReleases
        } catch (e: Exception) {
            UpdateResult.Failed
        }
    }

    /** "v0.10.1" is newer than "0.9.9": compares the dot-separated numbers, ignoring a leading "v". */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
