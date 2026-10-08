package net.webstas.sleepsense

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "NoiseMonitor"

/** Configurable sensitivity levels for noise spike detection. */
enum class NoiseSensitivity(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val overBaselineDb: Int,
    val minDb: Int,
    val requiredSeconds: Int,
) {
    LOW("low", R.string.noise_sens_low_title, R.string.noise_sens_low_desc, 18, 56, 2),
    MEDIUM("medium", R.string.noise_sens_medium_title, R.string.noise_sens_medium_desc, 14, 48, 1),
    HIGH("high", R.string.noise_sens_high_title, R.string.noise_sens_high_desc, 10, 42, 1),
}

/** Settings and the saved clips. */
object NoiseClips {
    private const val PREFS = "noise"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val MAX_CLIPS = 30

    // Off until the user switches it on: it uses the microphone
    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
    }

    fun sensitivity(context: Context): NoiseSensitivity {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SENSITIVITY, NoiseSensitivity.MEDIUM.id)
        return NoiseSensitivity.entries.firstOrNull { it.id == id } ?: NoiseSensitivity.MEDIUM
    }

    fun setSensitivity(context: Context, sens: NoiseSensitivity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_SENSITIVITY, sens.id) }
    }

    fun dir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }

    /** Clip files, newest first. Names are clip_<epochMillis>_<peakDb>.wav */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("clip_") && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun prune(context: Context) = list(context).drop(MAX_CLIPS).forEach { it.delete() }
}

/**
 * Listens through the phone microphone while sleep is being tracked. Keeps a running average of the
 * room level; when the level jumps well above it, saves a clip (a few seconds before the spike plus
 * a few after) as a WAV file. Levels are approximate dB: phone microphones are not calibrated.
 */
class NoiseMonitor(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK = SAMPLE_RATE / 10        // 0.1 s per read
        private const val PRE_ROLL_CHUNKS = 50            // 5 s kept before a spike
        private const val POST_ROLL_CHUNKS = 250          // 25 s recorded after it
        private const val COOLDOWN_CHUNKS = 450           // 45 s before another clip
        private const val WARMUP_SECONDS = 15
        private const val BASELINE_SECONDS = 300.0        // "average" = about the last 5 minutes
    }

    @Volatile var listening = false
        private set
    @Volatile var lastDb = 0
        private set
    @Volatile var avg60 = 0
        private set
    private var thread: Thread? = null
    @Volatile private var resetBaselineRequested = false

    fun resetBaseline() {
        resetBaselineRequested = true
    }

    @Synchronized
    fun start() {
        if (listening) return
        listening = true
        resetBaselineRequested = false
        thread = Thread({ loop() }, "noise-monitor").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        listening = false
        thread?.join(2000)
        thread = null
        lastDb = 0
        avg60 = 0
    }

    private fun dbOf(chunks: List<ShortArray>): Int {
        var sum = 0.0
        var n = 0
        for (c in chunks) for (s in c) { sum += s.toDouble() * s; n++ }
        val rms = sqrt(sum / max(n, 1))
        // dBFS shifted into a familiar-looking range; not a calibrated sound level
        return (20 * log10(max(rms, 1.0) / 32768.0) + 90).toInt().coerceIn(0, 100)
    }

    @Suppress("MissingPermission") // checked by the caller before start()
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone not available")
            listening = false
            return
        }
        record.startRecording()
        Log.i(TAG, "listening")

        val ring = ArrayDeque<ShortArray>()            // the last few seconds, always
        val second = ArrayList<ShortArray>()
        val recentDb = ArrayDeque<Int>()               // last 60 per-second levels
        var baseline = -1.0
        var seconds = 0
        var overCount = 0
        var clip: ArrayList<ShortArray>? = null
        var clipLeft = 0
        var clipPeak = 0
        var clipStart = 0L
        var cooldown = 0

        try {
            while (listening) {
                if (resetBaselineRequested) {
                    baseline = -1.0
                    cooldown = COOLDOWN_CHUNKS
                    resetBaselineRequested = false
                }

                val buf = ShortArray(CHUNK)
                var read = 0
                while (read < CHUNK && listening) {
                    val n = record.read(buf, read, CHUNK - read)
                    if (n < 0) { listening = false; break }
                    read += n
                }
                if (read < CHUNK) break

                ring.addLast(buf)
                if (ring.size > PRE_ROLL_CHUNKS) ring.removeFirst()
                second.add(buf)
                clip?.add(buf)
                if (cooldown > 0) cooldown--

                if (clip != null && --clipLeft <= 0) {
                    save(clip, clipStart, clipPeak)
                    clip = null
                    cooldown = COOLDOWN_CHUNKS
                }

                if (second.size < 10) continue
                val db = dbOf(second)
                second.clear()
                seconds++
                lastDb = db
                recentDb.addLast(db)
                if (recentDb.size > 60) recentDb.removeFirst()
                avg60 = recentDb.average().toInt()

                if (clip != null) {
                    clipPeak = max(clipPeak, db)
                    continue
                }
                // The room's average only learns from ordinary seconds, never from the spike itself
                val sens = NoiseClips.sensitivity(context)
                val isSpike = baseline >= 0 && db >= baseline + sens.overBaselineDb && db >= sens.minDb
                if (!isSpike) {
                    baseline = if (baseline < 0) db.toDouble() else baseline + (db - baseline) / BASELINE_SECONDS
                    overCount = 0
                    continue
                }
                overCount++
                if (overCount >= sens.requiredSeconds && seconds > WARMUP_SECONDS && cooldown == 0) {
                    // Loud enough for long enough above the room's average: record, starting a few seconds earlier
                    Log.i(TAG, "spike: $db dB over baseline ${baseline.toInt()} (sens=${sens.id})")
                    clip = ArrayList(ring)
                    clipLeft = POST_ROLL_CHUNKS
                    clipPeak = db
                    clipStart = System.currentTimeMillis() - PRE_ROLL_CHUNKS * 100L
                    overCount = 0
                }
            }
            clip?.let { save(it, clipStart, clipPeak) } // stopped mid-clip: keep what there is
        } finally {
            record.stop()
            record.release()
            Log.i(TAG, "stopped")
        }
    }

    private fun save(chunks: List<ShortArray>, startMillis: Long, peakDb: Int) {
        val file = File(NoiseClips.dir(context), "clip_${startMillis}_$peakDb.wav")
        val pcmBytes = chunks.sumOf { it.size } * 2
        file.outputStream().buffered().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcmBytes)
            }
            out.write(header.array())
            val body = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in chunks) {
                body.clear()
                for (s in c) body.putShort(s)
                out.write(body.array(), 0, c.size * 2)
            }
        }
        NoiseClips.prune(context)
        Log.i(TAG, "saved ${file.name} ($pcmBytes bytes)")
    }
}
