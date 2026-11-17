package net.webstas.sleepsense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.io.File

/**
 * Blood sugar readings from xDrip+, which hands every new reading to other apps with its "Broadcast locally"
 * setting (Inter-app settings). They are kept, tracking or not, to be laid next to the nights in History.
 * Values are in mg/dL, as xDrip+ sends them.
 */
object GlucoseLog {
    private const val PREFS = "glucose"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRUNED = "pruned"
    private const val KEEP_MS = 40L * 24 * 3600 * 1000 // the watch keeps 35 days of nights
    private const val DAY_MS = 24L * 3600 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, on) }

    private fun file(context: Context) = File(context.filesDir, "glucose.csv")

    /** Records that a broadcast came in and what became of it, as (time, note). */
    fun noteBroadcast(context: Context, note: String) =
        prefs(context).edit { putString("last_broadcast", "${System.currentTimeMillis()}|$note") }

    fun lastBroadcast(context: Context): Pair<Long, String>? =
        prefs(context).getString("last_broadcast", null)?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?.let { (it[0].toLongOrNull() ?: return null) to it[1] }

    /** Adds a reading ("<millis>,<mg/dL>" per line); one xDrip+ sent twice is kept once. */
    @Synchronized
    fun add(context: Context, at: Long, mgdl: Int, now: Long = System.currentTimeMillis()) {
        if (mgdl !in 20..600 || at > now + 5 * 60_000 || at < now - KEEP_MS) return
        val f = file(context)
        if (f.exists() && f.readLines().takeLast(20).any { it.substringBefore(',') == at.toString() }) return
        f.appendText("$at,$mgdl\n")
        if (now - prefs(context).getLong(KEY_PRUNED, 0) > DAY_MS) {
            val kept = f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0) >= now - KEEP_MS }
            f.writeText(kept.joinToString("") { it + "\n" })
            prefs(context).edit { putLong(KEY_PRUNED, now) }
        }
    }

    /** The readings between [start] and [end] as (time in millis, mg/dL), oldest first. */
    @Synchronized
    fun readings(context: Context, start: Long, end: Long): List<Pair<Long, Int>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            val at = p.getOrNull(0)?.toLongOrNull()
            val mgdl = p.getOrNull(1)?.toIntOrNull()
            if (p.size == 2 && at != null && mgdl != null && at in start..end) at to mgdl else null
        }.sortedBy { it.first }
    }

    /** The newest reading as (time in millis, mg/dL), or null if none has arrived. */
    @Synchronized
    fun latest(context: Context): Pair<Long, Int>? =
        file(context).takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val p = line.split(',')
            (p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null) to (p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null)
        }?.maxByOrNull { it.first }
}

/**
 * Takes xDrip+'s local broadcast of a new reading, when the import is switched on. It is declared in the
 * manifest and also registered by [AlarmBridgeService] while that runs, so a broadcast that is not aimed at
 * this app's package (Android does not deliver those to a manifest receiver) still arrives. What happened to
 * the latest broadcast is kept for the settings card, so a silent import can be told from a missing one.
 */
class XdripReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!GlucoseLog.isEnabled(context)) {
            GlucoseLog.noteBroadcast(context, "ignored, import is off")
            return
        }
        val extras = intent.extras
        val mgdl = (extras?.get(EXTRA_VALUE) as? Number)?.toDouble() ?: 0.0
        val at = (extras?.get(EXTRA_TIME) as? Number)?.toLong()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (mgdl <= 0) {
            GlucoseLog.noteBroadcast(context, "no reading in it (extras: ${extras?.keySet()?.joinToString { it.substringAfterLast('.') } ?: "none"})")
            return
        }
        GlucoseLog.add(context, at, Math.round(mgdl).toInt())
        GlucoseLog.noteBroadcast(context, "${Math.round(mgdl)} mg/dL")
    }

    companion object {
        const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val EXTRA_VALUE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
        private const val EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    }
}
