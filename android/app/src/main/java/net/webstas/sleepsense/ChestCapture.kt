package net.webstas.sleepsense

import android.content.Context
import android.content.Intent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SECONDS_PER_CHUNK = 6 // 30 samples at 5 a second
private const val STALL_MS = 25_000L

/**
 * The chest breathing test: the watch (while tracking) sends its raw accelerometer axes, averaged to 5 a
 * second, in chunks. They are kept in chest.csv as "<received millis>,<chunk number>,x,y,z,x,y,z,..." (milli-g)
 * so the breathing can be worked out from them offline. Each test replaces the file.
 */
object ChestCapture {
    @Volatile
    var active = false
        private set

    @Volatile
    private var lastChunkAt = 0L

    private fun file(context: Context) = File(context.filesDir, "chest.csv")

    /** Starts a test; false when the watch is not tracking, since it only runs the accelerometer then. */
    fun start(context: Context): Boolean {
        if (!WidgetState.tracking(context)) return false
        // The command is collected through the bridge service, so make sure it is running
        context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        file(context).delete()
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
        val axes = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
        val line = StringBuilder().append(now).append(',').append(data[0].toInt() and 0xFF)
        while (axes.remaining() >= 2) line.append(',').append(axes.short.toInt())
        file(context).appendText(line.append('\n').toString())
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
