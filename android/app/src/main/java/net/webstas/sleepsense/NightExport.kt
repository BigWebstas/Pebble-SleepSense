package net.webstas.sleepsense

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "NightExport"

/**
 * Writes each finished night as a Markdown note with mermaid charts into a folder chosen in the app,
 * for a hot-folder sync (Joplin's, Syncthing and the like) to pick up. Runs whenever the history is
 * saved (see SessionStore.save), which is shortly after tracking stops. A night is written once; a
 * star rating given later rewrites the same file. Turning the export on skips the nights already
 * recorded, so the folder only ever gets new ones.
 */
object NightExport {
    private const val PREFS = "night_export"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER = "folder"
    private const val KEY_DONE = "done" // "start:stars" entries
    private const val KEY_LAST = "last"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun setFolder(context: Context, uri: Uri) = prefs(context).edit { putString(KEY_FOLDER, uri.toString()) }

    /** Switches the export on or off. Turning it on marks the nights recorded so far as already done. */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        if (!on) return
        val done = done(context).toMutableMap()
        val all = JSONArray(SessionStore.load(context))
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            if (!s.optBoolean("open")) done.putIfAbsent(s.optLong("start"), s.optInt("rating"))
        }
        saveDone(context, done)
    }

    /** The newest file written, as (time, file name), or null. */
    fun last(context: Context): Pair<Long, String>? =
        prefs(context).getString(KEY_LAST, null)?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?.let { (it[0].toLongOrNull() ?: return null) to it[1] }

    private fun done(context: Context): Map<Long, Int> =
        prefs(context).getStringSet(KEY_DONE, emptySet())!!.mapNotNull { e ->
            val p = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toIntOrNull() ?: return@mapNotNull null)
        }.toMap()

    private fun saveDone(context: Context, done: Map<Long, Int>) {
        val keep = done.entries.sortedByDescending { it.key }.take(60) // the history holds 35 days
        prefs(context).edit { putStringSet(KEY_DONE, keep.map { "${it.key}:${it.value}" }.toSet()) }
    }

    /** Writes the finished nights that are new, or whose rating changed. Quiet when it is off or has no folder. */
    @Synchronized
    fun sync(context: Context) {
        val tree = folder(context)
        if (!isEnabled(context) || tree == null) return
        try {
            val done = done(context).toMutableMap()
            val all = JSONArray(SessionStore.loadWithOximeter(context))
            for (i in 0 until all.length()) {
                val s = all.getJSONObject(i)
                if (s.optBoolean("open")) continue
                val start = s.optLong("start")
                val stars = s.optInt("rating")
                if (done[start] == stars) continue
                if (SessionStore.asleepMinutes(s) == 0L) { done[start] = stars; continue } // a test run or a mis-start
                val name = fileName(s)
                write(context, tree, name, markdown(s))
                done[start] = stars
                prefs(context).edit { putString(KEY_LAST, "${System.currentTimeMillis()}|$name") }
            }
            saveDone(context, done)
        } catch (e: Exception) {
            Log.w(TAG, "night export failed: $e")
        }
    }

    private fun write(context: Context, tree: Uri, name: String, text: String) {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        var doc: Uri? = null
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        // A generic type keeps the provider from adding its own extension to the name
        val target = doc ?: DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(tree, treeId), "application/octet-stream", name,
        ) ?: error("could not create $name")
        resolver.openOutputStream(target, "wt")!!.use { it.write(text.toByteArray()) }
    }

    private fun zone(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    private fun pad(n: Int) = n.toString().padStart(2, '0')
    private fun date(ms: Long) = zone(ms).let { "${it.year}-${pad(it.monthValue)}-${pad(it.dayOfMonth)}" }
    private fun time(ms: Long) = zone(ms).let { "${pad(it.hour)}:${pad(it.minute)}" }
    private fun stamp(ms: Long) = "${date(ms)} ${time(ms)}"
    private fun dur(ms: Long) = (ms / 60_000.0).roundToInt().let { "${it / 60}h ${pad(it % 60)}m" }

    fun fileName(s: JSONObject): String {
        val start = s.optLong("start")
        return "Sleep ${date(start)} ${time(start).replace(':', '-')}.md"
    }

    private class Span(val from: Long, val to: Long, val p: JSONObject)

    // A gap in the samples is unknown time, so a sample holds for 10 minutes at most (as in report-core.js)
    private fun spans(s: JSONObject): List<Span> {
        val samples = s.optJSONArray("samples") ?: return emptyList()
        val end = s.optLong("end")
        return (0 until samples.length()).map { i ->
            val p = samples.getJSONObject(i)
            val t = p.optLong("t")
            val next = if (i + 1 < samples.length()) samples.getJSONObject(i + 1).optLong("t") else end
            Span(t, min(max(next, t), t + 10 * 60_000), p)
        }
    }

    private val STAGES = listOf(0 to "Awake", 3 to "REM", 1 to "Light", 2 to "Deep")
    private val ALARM = mapOf(1 to "Alarm rang", 2 to "Snoozed", 0 to "Alarm stopped")
    private val ALARM_WORD = mapOf(1 to "rang", 2 to "snoozed", 0 to "stopped")

    /** The note for one finished night: a summary table, the stage timeline and one chart per signal. */
    fun markdown(s: JSONObject): String {
        val start = s.optLong("start")
        val end = s.optLong("end")
        val spans = spans(s)
        val mins = DoubleArray(4)
        for (sp in spans) sp.p.optInt("s").takeIf { it in 0..3 }?.let { mins[it] += (sp.to - sp.from) / 60_000.0 }
        fun avg(field: String): Int {
            val v = spans.map { it.p.optDouble(field, 0.0) }.filter { it > 0 }
            return if (v.isEmpty()) 0 else (v.sum() / v.size).roundToInt()
        }
        val lowestO2 = spans.map { it.p.optDouble("o2", 0.0) }.filter { it > 0 }.minOrNull()?.roundToInt()
        val snoozes = s.optInt("snoozes")
        val events = (s.optJSONArray("events") ?: JSONArray()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("t") in start..end && it.optInt("a") in ALARM }
        }
        val out = StringBuilder()
        out.append("# Sleep ${date(start)}\n\n")
        out.append("**${time(start)}–${time(end)}** (${dur(end - start)})")
        if (s.optInt("rating") in 1..5) out.append(" · **Rated:** ${s.optInt("rating")}/5")
        out.append("\n\n| Awake | Light | Deep | REM | Avg HR | Avg breathing | Lowest SpO2 | Avg HRV | Avg noise | Snoozes |\n")
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n")
        fun v(n: Int, unit: String) = if (n > 0) "$n $unit" else "-"
        out.append("| ${mins[0].roundToInt()}m | ${mins[1].roundToInt()}m | ${mins[2].roundToInt()}m | ${mins[3].roundToInt()}m | ")
        out.append("${v(avg("hr"), "bpm")} | ${v(avg("br"), "/min")} | ${lowestO2?.let { "$it %" } ?: "-"} | ${v(avg("hrv"), "ms")} | ${v(avg("n"), "dB")} | ")
        out.append(if (snoozes > 0) "$snoozes (${(s.optLong("snoozeSec") / 60.0).roundToInt()} min)" else "-")
        out.append(" |\n\n")
        if (events.isNotEmpty()) {
            out.append("**Alarm:** ${events.joinToString(" · ") { "${ALARM_WORD[it.optInt("a")]} ${time(it.optLong("t"))}" }}\n\n")
        }
        out.append(gantt(spans, events)).append("\n\n")
        for (c in listOf(
            Chart("m", "Movement (accelerometer activity)", "activity", 0, null, true),
            Chart("hr", "Heart rate", "bpm", null, null),
            Chart("br", "Breathing rate (chest)", "breaths/min", null, null),
            Chart("o2", "Blood oxygen (lowest SpO2 per interval)", "%", null, 100),
            Chart("hrv", "HRV (RMSSD of the oximeter pulse)", "ms", null, null),
            Chart("g", "Blood sugar (xDrip, mean per interval)", "mg/dL", null, null),
            Chart("l", "Ambient light level (1 dark to 4 bright)", "level", 0, 4),
            Chart("n", "Room noise", "dB", null, null),
        )) chart(s, c)?.let { out.append(it).append("\n\n") }
        return out.toString()
    }

    private fun gantt(spans: List<Span>, events: List<JSONObject>): String {
        class Seg(val stage: Int, val from: Long, var to: Long)
        val merged = mutableListOf<Seg>()
        for (sp in spans) {
            val last = merged.lastOrNull()
            val stage = sp.p.optInt("s")
            if (last != null && last.stage == stage && last.to == sp.from) last.to = sp.to else merged += Seg(stage, sp.from, sp.to)
        }
        val out = mutableListOf("```mermaid", "gantt", "    title Sleep stages", "    dateFormat YYYY-MM-DD HH:mm", "    axisFormat %H:%M")
        for ((value, name) in STAGES) {
            val segs = merged.filter { it.stage == value && it.to > it.from }
            if (segs.isEmpty()) continue
            out += "    section $name"
            segs.forEach { out += "    $name :${stamp(it.from)}, ${stamp(it.to)}" }
        }
        if (events.isNotEmpty()) {
            out += "    section Alarm"
            events.forEach { out += "    ${ALARM[it.optInt("a")]} :milestone, ${stamp(it.optLong("t"))}, 0m" }
        }
        out += "```"
        return out.joinToString("\n")
    }

    private class Chart(val field: String, val title: String, val axis: String, val lo: Int?, val hi: Int?, val keepZero: Boolean = false)

    /** One mermaid line chart: the field resampled to about 24 points, a gap carrying the last reading. */
    private fun chart(s: JSONObject, c: Chart): String? {
        val start = s.optLong("start")
        val span = s.optLong("end") - start
        val quarter = 15 * 60_000L
        val step = max(quarter, ceil(span / 24.0 / quarter).toLong() * quarter)
        val n = max(ceil(span.toDouble() / step).toInt(), 1)
        val sums = DoubleArray(n)
        val counts = IntArray(n)
        val samples = s.optJSONArray("samples") ?: return null
        for (i in 0 until samples.length()) {
            val p = samples.getJSONObject(i)
            if (!p.has(c.field)) continue
            val x = p.optDouble(c.field)
            if (!c.keepZero && x <= 0) continue
            val k = min(((p.optLong("t") - start) / step).toInt(), n - 1).coerceAtLeast(0)
            sums[k] += x
            counts[k]++
        }
        val first = counts.indexOfFirst { it > 0 }
        if (first < 0) return null
        var prev = sums[first] / counts[first]
        val values = (0 until n).map { j ->
            if (counts[j] > 0) prev = sums[j] / counts[j]
            prev.roundToInt()
        }
        val labels = (0 until n).map { "\"${time(start + it * step)}\"" }
        val lo = c.lo ?: (values.min() / 5 * 5)
        val hi = c.hi ?: max(ceil(values.max() / 5.0).toInt() * 5, lo + 5)
        return listOf(
            "```mermaid", "xychart-beta", "    title \"${c.title}\"", "    x-axis [${labels.joinToString(", ")}]",
            "    y-axis \"${c.axis}\" $lo --> $hi", "    line [${values.joinToString(", ")}]", "```",
        ).joinToString("\n")
    }
}
