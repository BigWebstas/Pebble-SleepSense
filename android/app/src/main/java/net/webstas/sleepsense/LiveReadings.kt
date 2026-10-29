package net.webstas.sleepsense

import android.content.Context

/** The newest value of each data stream (watch sensors, oximeter), for the live readings in the app. */
object LiveReadings {
    enum class Stream(val labelRes: Int) {
        STAGE(R.string.live_stage),
        MOVEMENT(R.string.live_movement),
        LIGHT(R.string.live_light),
        SOUND(R.string.live_sound),
        HEART_RATE(R.string.live_heart_rate),
        SPO2(R.string.live_spo2),
        OXIMETER_PULSE(R.string.live_oximeter_pulse),
        BREATHING(R.string.live_breathing),
    }

    class Reading(val value: Int, val at: Long)

    private val latest = java.util.concurrent.ConcurrentHashMap<Stream, Reading>()

    fun note(stream: Stream, value: Int?, now: Long = System.currentTimeMillis()) {
        if (value != null) latest[stream] = Reading(value, now)
    }

    fun get(stream: Stream): Reading? = latest[stream]

    private const val WAVE_SAMPLES = 400 // the oximeter sends about 50 a second: 8 s
    private const val WAVE_SAMPLE_MS = 20L
    private const val WAVE_STALE_MS = 3_000L
    private val waveValues = IntArray(WAVE_SAMPLES)
    private val waveTimes = LongArray(WAVE_SAMPLES)
    private var waveCount = 0 // samples written so far, the newest at (waveCount - 1) % WAVE_SAMPLES
    private var waveClock = 0L // when the newest sample was taken

    class Waveform(val times: LongArray, val values: IntArray)

    /**
     * Adds the oximeter's waveform bytes: 0 to 127, with 128 added on the one spike sample of each beat.
     * Frames arrive in bursts of 5, so the samples are put on an even 20 ms clock that follows the arrivals,
     * which lets the view scroll smoothly instead of in steps.
     */
    @Synchronized
    fun noteWaveform(samples: ByteArray, now: Long = System.currentTimeMillis()) {
        val ideal = now - (samples.size - 1) * WAVE_SAMPLE_MS
        var t = waveClock + WAVE_SAMPLE_MS
        if (t < ideal - 100 || t > ideal + 100) t = ideal // the first frame, a dropout, or drift
        for (b in samples) {
            val i = waveCount++ % WAVE_SAMPLES
            waveValues[i] = b.toInt() and 0x7F
            waveTimes[i] = t
            waveClock = t
            t += WAVE_SAMPLE_MS
        }
    }

    @Synchronized
    fun hasWaveform(now: Long = System.currentTimeMillis()) = waveCount > 0 && now - waveClock <= WAVE_STALE_MS

    /** The samples taken between [from] and [to] (millis), oldest first, or null when the oximeter has gone quiet. */
    @Synchronized
    fun waveform(from: Long, to: Long, now: Long = System.currentTimeMillis()): Waveform? {
        if (!hasWaveform(now)) return null
        val n = minOf(waveCount, WAVE_SAMPLES)
        val times = LongArray(n)
        val values = IntArray(n)
        var kept = 0
        for (k in waveCount - n until waveCount) { // oldest first, without allocating per sample: this runs every frame
            val i = k % WAVE_SAMPLES
            if (waveTimes[i] in from..to) {
                times[kept] = waveTimes[i]
                values[kept++] = waveValues[i]
            }
        }
        return Waveform(times.copyOf(kept), values.copyOf(kept))
    }

    /** "Name: value (12 s ago)" per stream, "--" for one not heard from yet. */
    fun describe(context: Context, now: Long = System.currentTimeMillis()): String =
        Stream.entries.joinToString("\n") { stream ->
            val r = latest[stream]
            val text = if (r == null) "--" else context.getString(
                R.string.live_value_ago, valueText(context, stream, r.value), ago(context, now - r.at),
            )
            context.getString(stream.labelRes) + ": " + text
        }

    private fun valueText(context: Context, stream: Stream, v: Int): String = when (stream) {
        Stream.STAGE -> context.getString(
            when (v) {
                1 -> R.string.live_stage_light
                2 -> R.string.live_stage_deep
                3 -> R.string.live_stage_rem
                else -> R.string.live_stage_awake
            },
        )
        Stream.LIGHT -> context.getString(
            when (v) {
                1 -> R.string.live_light_very_dark
                2 -> R.string.live_light_dark
                3 -> R.string.live_light_light
                4 -> R.string.live_light_very_light
                else -> R.string.live_light_unknown
            },
        )
        Stream.MOVEMENT -> v.toString()
        Stream.SOUND -> context.getString(R.string.live_unit_percent, v)
        Stream.HEART_RATE, Stream.OXIMETER_PULSE -> context.getString(R.string.live_unit_bpm, v)
        Stream.SPO2 -> context.getString(R.string.live_unit_percent, v)
        Stream.BREATHING -> context.getString(R.string.live_unit_per_minute, v)
    }

    private fun ago(context: Context, ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> context.getString(R.string.live_ago_seconds, s)
            s < 3600 -> context.getString(R.string.live_ago_minutes, s / 60)
            else -> context.getString(R.string.live_ago_hours, s / 3600)
        }
    }
}
