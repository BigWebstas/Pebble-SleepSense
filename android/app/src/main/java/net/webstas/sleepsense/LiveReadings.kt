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
    }

    class Reading(val value: Int, val at: Long)

    private val latest = java.util.concurrent.ConcurrentHashMap<Stream, Reading>()

    fun note(stream: Stream, value: Int?, now: Long = System.currentTimeMillis()) {
        if (value != null) latest[stream] = Reading(value, now)
    }

    fun get(stream: Stream): Reading? = latest[stream]

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
