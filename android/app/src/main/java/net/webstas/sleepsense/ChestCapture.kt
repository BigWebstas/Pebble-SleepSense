package net.webstas.sleepsense

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

private const val PREFS = "chest"
private const val KEY_ENABLED = "enabled"
private const val STALL_MS = 25_000L
private const val RESEND_MS = 20_000L // the same rate is sent to the watch again after this long, so it never goes stale

/**
 * Chest breathing rate. While it is on and the watch is tracking, the watch sends its raw accelerometer axes,
 * averaged to 5 a second, in chunks of 6 seconds. Once a minute of them is in, every chunk gives a breathing
 * rate (see [BreathingRate]): shown under Live Readings, sent on to the watch's screen, and kept in
 * chest_rate.csv as "<millis>,<breaths a minute>,<tone>", or "<millis>,," when there was no reading. The file
 * holds the latest tracking session.
 */
object ChestCapture {
    private val window = Array(3) { DoubleArray(BreathingRate.WINDOW_SAMPLES) } // the newest minute, as a ring
    private var windowCount = 0 // samples since the window was last emptied
    private var lastSeq = -1
    private var lastChunkAt = 0L
    private var lastSentRate = -1
    private var lastSentAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rateFile(context: Context) = File(context.filesDir, "chest_rate.csv")

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        if (on) {
            // The command is collected through the bridge service, so make sure it is running
            context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
            if (WidgetState.tracking(context)) Commands.queueChestStart()
        } else {
            Commands.queueChestStop()
            Commands.queueBreathing(0) // clears the rate on the watch's screen
        }
    }

    /** A new tracking session starts with an empty window and graph. */
    @Synchronized
    fun onTrackingStarted(context: Context) {
        rateFile(context).delete()
        windowCount = 0
        lastSeq = -1
        lastChunkAt = 0L
        lastSentRate = -1
    }

    /**
     * True when it is on but no chunk has come for a while although the watch is tracking (the check is made
     * from the watch's check-ins): tracking has just started, or the watch app was closed and relaunched, which
     * loses it, so the start is sent again. The chunk numbering then restarts.
     */
    fun needsRestart(context: Context, now: Long = System.currentTimeMillis()) =
        isEnabled(context) && now - lastChunkAt > STALL_MS

    /** One chunk from the watch: its number, then x, y, z as little-endian 16-bit values per sample. */
    @Synchronized
    fun addChunk(context: Context, data: ByteArray, now: Long = System.currentTimeMillis()) {
        if (!isEnabled(context) || data.size < 1 + 6) return
        lastChunkAt = now
        val seq = data[0].toInt() and 0xFF
        if (lastSeq >= 0 && (seq - lastSeq + 256) % 256 != 1) windowCount = 0 // a chunk was missed, or it restarted
        lastSeq = seq
        val axes = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        while (axes.remaining() >= 6) {
            val slot = windowCount++ % BreathingRate.WINDOW_SAMPLES
            for (axis in 0 until 3) window[axis][slot] = axes.short.toDouble()
        }
        if (windowCount >= BreathingRate.WINDOW_SAMPLES) noteRate(context, now)
    }

    private fun noteRate(context: Context, now: Long) {
        val oldest = windowCount % BreathingRate.WINDOW_SAMPLES // the ring's oldest sample sits where the next goes
        val ordered = Array(3) { axis ->
            DoubleArray(BreathingRate.WINDOW_SAMPLES) { window[axis][(oldest + it) % BreathingRate.WINDOW_SAMPLES] }
        }
        val estimate = BreathingRate.estimate(ordered)
        val rate = if (estimate.reliable) estimate.perMinute.roundToInt() else 0
        if (estimate.reliable) {
            LiveReadings.note(LiveReadings.Stream.BREATHING, rate, now)
            val line = "%d,%.1f,%.2f\n".format(Locale.US, now, estimate.perMinute, estimate.tone)
            rateFile(context).appendText(line)
        } else {
            rateFile(context).appendText("$now,,\n")
        }
        // To the watch when it changes, and now and then when it doesn't
        if (rate != lastSentRate || now - lastSentAt >= RESEND_MS) {
            Commands.queueBreathing(rate)
            lastSentRate = rate
            lastSentAt = now
        }
    }

    /** The rates of the latest session as (time in millis, breaths a minute), null where there was no reading. */
    @Synchronized
    fun rates(context: Context): List<Pair<Long, Double?>> {
        val f = rateFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            p.getOrNull(0)?.toLongOrNull()?.let { it to p.getOrNull(1)?.toDoubleOrNull() }
        }
    }
}
