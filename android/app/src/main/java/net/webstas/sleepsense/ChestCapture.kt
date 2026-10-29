package net.webstas.sleepsense

import android.content.Context
import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val SECONDS_PER_CHUNK = 6 // 30 samples at 5 a second
private const val STALL_MS = 25_000L

/**
 * The chest breathing test: the watch (while tracking) sends its raw accelerometer axes, averaged to 5 a
 * second, in chunks. They are kept in chest.csv as "<received millis>,<chunk number>,x,y,z,x,y,z,..." (milli-g).
 * Once a minute of them has come in, every chunk also gives a breathing rate (see [BreathingRate]), shown live and
 * kept in chest_rate.csv as "<millis>,<breaths a minute>,<tone>", or "<millis>,," when there was no reading.
 * Each test replaces both files.
 */
object ChestCapture {
    @Volatile
    var active = false
        private set

    @Volatile
    private var lastChunkAt = 0L

    private val window = Array(3) { DoubleArray(BreathingRate.WINDOW_SAMPLES) } // the newest minute, as a ring
    private var windowCount = 0 // samples since the window was last emptied
    private var lastSeq = -1

    private fun file(context: Context) = File(context.filesDir, "chest.csv")

    private fun rateFile(context: Context) = File(context.filesDir, "chest_rate.csv")

    /** Starts a test; false when the watch is not tracking, since it only runs the accelerometer then. */
    fun start(context: Context): Boolean {
        if (!WidgetState.tracking(context)) return false
        // The command is collected through the bridge service, so make sure it is running
        context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        file(context).delete()
        rateFile(context).delete()
        windowCount = 0
        lastSeq = -1
        active = true
        lastChunkAt = System.currentTimeMillis()
        Commands.queueChestStart()
        return true
    }

    fun stop() {
        active = false
        Commands.queueChestStop()
    }

    /** One chunk from the watch: its number, then x, y, z as little-endian 16-bit values per sample. */
    @Synchronized
    fun addChunk(context: Context, data: ByteArray, now: Long = System.currentTimeMillis()) {
        if (!active || data.size < 1 + 6) return
        lastChunkAt = now
        val seq = data[0].toInt() and 0xFF
        val axes = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val line = StringBuilder().append(now).append(',').append(seq)
        if (lastSeq >= 0 && (seq - lastSeq + 256) % 256 != 1) windowCount = 0 // a chunk was missed, or the test restarted
        lastSeq = seq
        while (axes.remaining() >= 6) {
            val slot = windowCount++ % BreathingRate.WINDOW_SAMPLES
            for (axis in 0 until 3) {
                val v = axes.short.toInt()
                line.append(',').append(v)
                window[axis][slot] = v.toDouble()
            }
        }
        file(context).appendText(line.append('\n').toString())
        if (windowCount >= BreathingRate.WINDOW_SAMPLES) noteRate(context, now)
    }

    private fun noteRate(context: Context, now: Long) {
        val oldest = windowCount % BreathingRate.WINDOW_SAMPLES // the ring's oldest sample sits where the next goes
        val ordered = Array(3) { axis ->
            DoubleArray(BreathingRate.WINDOW_SAMPLES) { window[axis][(oldest + it) % BreathingRate.WINDOW_SAMPLES] }
        }
        val estimate = BreathingRate.estimate(ordered)
        if (estimate.reliable) {
            LiveReadings.note(LiveReadings.Stream.BREATHING, estimate.perMinute.roundToInt(), now)
            rateFile(context).appendText("$now,${"%.1f".format(java.util.Locale.US, estimate.perMinute)},${"%.2f".format(java.util.Locale.US, estimate.tone)}\n")
        } else {
            rateFile(context).appendText("$now,,\n")
        }
    }

    /** The rates of the latest test as (time in millis, breaths a minute), null where there was no reading. */
    @Synchronized
    fun rates(context: Context): List<Pair<Long, Double?>> {
        val f = rateFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            p.getOrNull(0)?.toLongOrNull()?.let { it to p.getOrNull(1)?.toDoubleOrNull() }
        }
    }

    /**
     * True when a test is on but no chunk has come for a while although the watch is tracking: the watch app
     * was closed and relaunched, which loses the test, so the start is sent again. The numbering then restarts.
     */
    fun needsRestart(now: Long = System.currentTimeMillis()) = active && now - lastChunkAt > STALL_MS

    /** How the test is going: chunks received and chunks the phone never got (gaps in the numbering). */
    @Synchronized
    fun summary(context: Context): Pair<Int, Int>? {
        val f = file(context)
        if (!f.exists()) return null
        val seqs = f.readLines().mapNotNull { it.split(',').getOrNull(1)?.toIntOrNull() }
        if (seqs.isEmpty()) return 0 to 0
        val missed = seqs.zipWithNext().sumOf { (a, b) -> ((b - a + 256) % 256 - 1).coerceAtLeast(0) }
        return seqs.size to missed
    }

    fun secondsOf(chunks: Int) = chunks * SECONDS_PER_CHUNK
}
