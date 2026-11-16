package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import kotlin.math.roundToInt

/** How one night went with the alarm: how often it was snoozed, and for how long in all. */
class SnoozeNight(val start: Long, val durationMin: Long, val snoozes: Int, val snoozeMin: Int)

/**
 * A suggestion from how often the alarm gets snoozed: if the last nights average [MIN_AVERAGE] snoozes or more,
 * the alarm is probably too early, so move it later by about 60% of the time spent snoozing, or widen the smart
 * wake window so the watch has more chances to wake the user in light sleep.
 */
object SnoozeAdvice {
    private const val PREFS = "snooze_advice"
    private const val KEY_SINCE = "since"
    private const val NIGHTS = 7
    private const val MIN_NIGHTS = 3
    private const val MIN_AVERAGE = 2.0
    private const val MIN_NIGHT_MIN = 180L // shorter sessions are naps
    private const val DEFAULT_SNOOZE_MIN = 10

    class Advice(val nights: Int, val averageSnoozes: Double, val averageSnoozeMin: Int, val shiftMin: Int, val widerWindow: Int?)

    fun compute(all: List<SnoozeNight>, window: Int?): Advice? {
        val nights = all.filter { it.durationMin >= MIN_NIGHT_MIN }.takeLast(NIGHTS)
        if (nights.size < MIN_NIGHTS) return null
        val average = nights.sumOf { it.snoozes }.toDouble() / nights.size
        if (average < MIN_AVERAGE) return null
        val snoozeMin = nights.map { if (it.snoozeMin > 0) it.snoozeMin else it.snoozes * DEFAULT_SNOOZE_MIN }.average()
        val shift = ((snoozeMin * 0.6) / 15).roundToInt().coerceIn(1, 4) * 15 // 15 to 60 minutes, in quarter hours
        val wider = window?.let { w -> listOf(15, 30, 45).firstOrNull { it > w } }
        return Advice(nights.size, average, snoozeMin.roundToInt(), shift, wider)
    }

    /** [minutes] after midnight moved later by [shift] minutes, wrapping past midnight. */
    fun moved(minutes: Int, shift: Int) = (minutes + shift) % (24 * 60)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The suggestion for now, from the nights since the alarm was last changed on its advice or the advice was put aside. */
    fun current(context: Context): Advice? {
        val since = prefs(context).getLong(KEY_SINCE, 0)
        val all = JSONArray(SessionStore.load(context))
        val nights = (0 until all.length()).map { all.getJSONObject(it) }
            .filter { !it.optBoolean("open") && it.optLong("end") > it.optLong("start") && it.optLong("start") > since }
            .map {
                SnoozeNight(
                    it.optLong("start"),
                    (it.optLong("end") - it.optLong("start")) / 60_000,
                    it.optInt("snoozes"),
                    (it.optLong("snoozeSec") / 60).toInt(),
                )
            }
        return compute(nights, PhoneAlarmSync.smartWindowMinutes(context))
    }

    /** Hides the suggestion until [MIN_NIGHTS] new nights have been recorded. */
    fun putAside(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_SINCE, now) }
    }
}
