package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pulse rate variability (RMSSD, the beat-to-beat variation) from the oximeter's waveform file, in windows of
 * [WINDOW_MS]. The file holds "<millis>,<5 bytes>" per frame (see OximeterLog); the oximeter delivers about 50
 * samples a second, so each sample is about 20 ms and beats are timed by fitting a parabola round each peak.
 * This is the pulse at the finger, not an ECG: it follows heart rate variability closely at rest, but the 20 ms
 * step puts a floor under the smallest values it can tell apart.
 */
object PulseVariability {
    const val WINDOW_MS = 5 * 60_000L
    private const val GAP_S = 0.3 // frames further apart than this start a new stretch
    private const val MIN_FRAMES = 200
    private const val MIN_BEAT_GAP_S = 0.4
    private const val NEIGHBOURS = 5 // beats either side whose median says what an interval should be
    private const val TOLERANCE = 0.25 // an interval further than this from that median is a missed or doubled beat
    private const val MIN_PAIRS = 100 // pairs of clean neighbouring intervals a window needs
    private const val MIN_COVERAGE = 0.8 // share of the window the beats must span, so a cut-off window is not counted
    private const val MIN_CLEAN = 0.85 // share of a window's intervals that must be clean

    class Window(val at: Long, val rmssdMs: Double)

    private class Beat(val at: Double, val interval: Double, var clean: Boolean = false) // seconds

    /** RMSSD per window for the waveform between [start] and [end] (millis); windows without enough clean beats are left out. */
    fun windows(file: File, start: Long, end: Long): List<Window> {
        if (!file.exists()) return emptyList()
        val beats = ArrayList<Beat>()
        val times = ArrayList<Double>()
        val samples = ArrayList<Int>()
        fun finish() {
            if (times.size >= MIN_FRAMES) beats += beatsOf(times, samples)
            times.clear()
            samples.clear()
        }
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split(',')
                if (p.size != 6) continue
                val ms = p[0].toLongOrNull() ?: continue
                if (ms < start || ms > end) continue
                val t = ms / 1000.0
                if (times.isNotEmpty() && t - times.last() > GAP_S) finish()
                times += t
                for (i in 1..5) samples += (p[i].toIntOrNull() ?: 0) and 0x7F // 128 is added on a beat's spike sample
            }
        }
        finish()
        markClean(beats)
        return group(beats)
    }

    /** Beats of one unbroken stretch; sample times are spread evenly from the frames' first to last time. */
    private fun beatsOf(frameTimes: List<Double>, raw: List<Int>): List<Beat> {
        val n = (frameTimes.size - 1) * 5
        val fs = n / (frameTimes.last() - frameTimes.first())
        val t0 = frameTimes.first()
        val x = DoubleArray(raw.size) { raw[it].toDouble() }
        val y = smooth(subtract(x, movingMean(x, (1.2 * fs).toInt() or 1)), 3)

        val peaks = ArrayList<Int>()
        var last = -9.0
        for (i in 2 until y.size - 2) {
            if (y[i] > y[i - 1] && y[i] >= y[i + 1] && y[i] > 0) {
                val at = t0 + i / fs
                if (at - last < MIN_BEAT_GAP_S) {
                    if (peaks.isNotEmpty() && y[i] > y[peaks.last()]) {
                        peaks[peaks.lastIndex] = i
                        last = at
                    }
                    continue
                }
                peaks += i
                last = at
            }
        }
        if (peaks.size < 3) return emptyList()
        val floor = 0.3 * median(peaks.map { y[it] })
        val times = peaks.filter { y[it] > floor }.map {
            val a = y[it - 1]; val b = y[it]; val c = y[it + 1]
            val d = a - 2 * b + c
            t0 + (it + if (d != 0.0) 0.5 * (a - c) / d else 0.0) / fs
        }
        return (1 until times.size).map { Beat(times[it], times[it] - times[it - 1]) }
    }

    private fun markClean(beats: List<Beat>) {
        for (i in beats.indices) {
            val near = beats.subList(max(0, i - NEIGHBOURS), minOf(beats.size, i + NEIGHBOURS + 1)).map { it.interval }
            val m = median(near)
            beats[i].clean = abs(beats[i].interval - m) < TOLERANCE * m
        }
    }

    private fun group(beats: List<Beat>): List<Window> {
        if (beats.isEmpty()) return emptyList()
        val first = beats.first().at
        val span = WINDOW_MS / 1000.0
        return beats.groupBy { ((it.at - first) / span).toInt() }.mapNotNull { (index, inWindow) ->
            // Neighbouring beats of one stretch: the second interval ends where the first began (not across a gap)
            val steps = (0 until inWindow.size - 1).filter {
                inWindow[it].clean && inWindow[it + 1].clean &&
                    abs(inWindow[it + 1].at - inWindow[it].at - inWindow[it + 1].interval) < 1e-6
            }
                .map { inWindow[it + 1].interval - inWindow[it].interval }
            val covered = inWindow.last().at - inWindow.first().at >= MIN_COVERAGE * WINDOW_MS / 1000.0
            if (!covered || steps.size < MIN_PAIRS || inWindow.count { it.clean } < MIN_CLEAN * inWindow.size) return@mapNotNull null
            val rmssd = sqrt(steps.sumOf { it * it } / steps.size) * 1000
            Window(((first + (index + 0.5) * span) * 1000).toLong(), rmssd)
        }.sortedBy { it.at }
    }

    private fun movingMean(x: DoubleArray, width: Int): DoubleArray {
        val sums = DoubleArray(x.size + 1)
        for (i in x.indices) sums[i + 1] = sums[i] + x[i]
        val half = width / 2
        return DoubleArray(x.size) {
            val lo = max(0, it - half)
            val hi = minOf(x.size, it + half + 1)
            (sums[hi] - sums[lo]) / (hi - lo)
        }
    }

    private fun subtract(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] - b[it] }

    private fun smooth(x: DoubleArray, width: Int) = movingMean(x, width)

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * The HRV windows of past nights, kept for the History graphs ("<millis>,<RMSSD ms>" per line). The waveform
 * they come from only holds the latest oximeter run, so each night's windows are worked out once, when it ends
 * (or when History first opens for the latest night, if that was before this log existed), and kept here.
 */
object HrvLog {
    private const val PREFS = "hrv_log"
    private const val KEY_TRIED = "tried" // start of the night whose waveform was last worked out
    private const val KEEP_MS = 40L * 24 * 3600 * 1000 // the watch keeps 35 days of nights

    private fun file(context: Context) = File(context.filesDir, "hrv.csv")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The windows between [start] and [end] as (time in millis, RMSSD in ms), oldest first. */
    @Synchronized
    fun readings(context: Context, start: Long, end: Long): List<Pair<Long, Double>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            val at = p.getOrNull(0)?.toLongOrNull()
            val rmssd = p.getOrNull(1)?.toDoubleOrNull()
            if (p.size == 2 && at != null && rmssd != null && at in start..end) at to rmssd else null
        }.sortedBy { it.first }
    }

    /** Works out the night [start]..[end] from the waveform and keeps it, replacing what was kept for those times. */
    fun record(context: Context, start: Long, end: Long, now: Long = System.currentTimeMillis()) {
        val windows = PulseVariability.windows(OximeterLog.waveFile(context), start, end)
        synchronized(this) {
            val f = file(context)
            val kept = if (f.exists()) f.readLines().filter { line ->
                val at = line.substringBefore(',').toLongOrNull() ?: return@filter false
                at !in start..end && at >= now - KEEP_MS
            } else emptyList()
            f.writeText((kept + windows.map { "${it.at},${"%.1f".format(Locale.US, it.rmssdMs)}" }).joinToString("") { it + "\n" })
            prefs(context).edit { putLong(KEY_TRIED, start) }
        }
    }

    /**
     * For a finished night whose HRV was never kept (it ended before this log existed): works it out from the
     * waveform if that still holds this night's run, which is the case when it starts inside the night. Done once.
     */
    fun backfill(context: Context, start: Long, end: Long) {
        if (!OximeterLog.hrvEnabled(context) || prefs(context).getLong(KEY_TRIED, 0) == start) return
        val wave = OximeterLog.waveFile(context)
        val first = wave.takeIf { it.exists() }?.bufferedReader()?.use { it.readLine() }?.substringBefore(',')?.toLongOrNull()
        if (first == null || first !in start..end) return // the waveform is from another night, or empty
        record(context, start, end)
    }
}
