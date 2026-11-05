package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private const val CLAUDE_MODEL = "claude-opus-5-5"
private const val GEMINI_MODEL = "gemini-2.5-flash"
private const val NIGHTS = 14
private const val MAX_HOLD_MS = 10L * 60_000 // a gap in the samples is unknown time, as in report-core.js

enum class CoachProvider(val id: String, val titleRes: Int) {
    CLAUDE("claude", R.string.coach_provider_claude),
    GEMINI("gemini", R.string.coach_provider_gemini),
}

/** The optional AI sleep summary: whether it is on, the provider and its API key, and the last answer. Off until the user switches it on. */
object CoachPrefs {
    private const val PREFS = "ai_coach" // not part of backups: it holds the key
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_LAST = "last"
    private const val KEY_LAST_AT = "last_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, on) }

    fun provider(context: Context): CoachProvider =
        CoachProvider.entries.firstOrNull { it.id == prefs(context).getString(KEY_PROVIDER, null) } ?: CoachProvider.CLAUDE

    fun setProvider(context: Context, provider: CoachProvider) = prefs(context).edit { putString(KEY_PROVIDER, provider.id) }

    // One key per provider, so switching back and forth does not ask again
    fun apiKey(context: Context, provider: CoachProvider = provider(context)): String? =
        prefs(context).getString("api_key_${provider.id}", null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, provider: CoachProvider, key: String) =
        prefs(context).edit { putString("api_key_${provider.id}", key.trim()) }

    fun last(context: Context): Pair<Long, CoachAdvice>? {
        val json = prefs(context).getString(KEY_LAST, null) ?: return null
        return runCatching { prefs(context).getLong(KEY_LAST_AT, 0) to CoachAdvice.parse(json) }.getOrNull()
    }

    fun setLast(context: Context, json: String, at: Long) = prefs(context).edit {
        putString(KEY_LAST, json)
        putLong(KEY_LAST_AT, at)
    }
}

class NextAlarm(val label: String, val time: String, val reason: String)

/** What the model answers: a summary, advice, and the settings it suggests. */
class CoachAdvice(
    val summary: String,
    val recommendations: List<String>,
    val sleepMinutes: Int?,
    val bedtime: String?,
    val alarm: String?,
    val smartWindow: Int?,
    val nextAlarms: List<NextAlarm>,
) {
    /** The suggested alarm as (hour, minute), if it is a valid time. */
    fun alarmTime(): Pair<Int, Int>? = alarm?.let { t ->
        runCatching { LocalTime.parse(t) }.getOrNull()?.let { it.hour to it.minute }
    }

    companion object {
        fun parse(text: String): CoachAdvice {
            // Tolerates a code fence or a sentence around the object
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            fun strings(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return CoachAdvice(
                summary = json.optString("summary"),
                recommendations = strings("recommendations"),
                sleepMinutes = json.optInt("sleep_minutes").takeIf { it in 60..960 },
                bedtime = json.optString("bedtime").takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) },
                alarm = json.optString("alarm").takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) }?.padStart(5, '0'),
                smartWindow = json.optInt("smart_window_min").takeIf { it in listOf(15, 30, 45) },
                nextAlarms = json.optJSONArray("next_alarms")?.let { a ->
                    (0 until a.length()).map { i ->
                        a.getJSONObject(i).let { NextAlarm(it.optString("label"), it.optString("time"), it.optString("reason")) }
                    }
                } ?: emptyList(),
            )
        }
    }
}

object SleepCoach {
    private val SYSTEM = """
        You are a sleep coach inside a sleep-tracking app. The user wears a smartwatch that tracks sleep stages, heart rate and
        movement; some nights also have blood oxygen, room noise, breathing rate, blood sugar (mg/dL) and a 1-5 star rating they
        gave. You get a compact summary of their latest sessions (sessions under about 2 hours are naps) and their current alarm
        settings. Be concrete and honest about what the data does and does not show; it is a consumer wearable, not a medical
        device, so do not diagnose, and suggest seeing a doctor only for something that clearly warrants it (for example repeated
        low blood oxygen). Base every suggestion on the numbers you were given.

        Reply with only one JSON object, no other text, in this shape:
        {"summary": "3-5 sentences on how they have been sleeping and any trend",
         "recommendations": ["up to 5 short, specific suggestions"],
         "sleep_minutes": suggested time asleep per night, as whole minutes,
         "bedtime": "HH:MM, 24-hour",
         "alarm": "HH:MM, 24-hour, the alarm time to use",
         "smart_window_min": 15 or 30 or 45 (how long before the alarm time the watch may wake them in light sleep),
         "next_alarms": [{"label": "e.g. Tonight / Weekday / Weekend", "time": "HH:MM", "reason": "short"}]}
        next_alarms lists up to 3 upcoming alarms worth setting, soonest first.
    """.trimIndent()

    /** One line of facts per session, newest last, so the request stays small. */
    fun digest(context: Context, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val all = JSONArray(SessionStore.loadWithOximeter(context)) // includes imported blood sugar, if on
        val finished = (0 until all.length()).map { all.getJSONObject(it) }.filter { !it.optBoolean("open") && it.optLong("end") > it.optLong("start") }
        val lines = finished.takeLast(NIGHTS).map { s ->
            val start = s.optLong("start")
            val end = s.optLong("end")
            val samples = s.optJSONArray("samples") ?: JSONArray()
            val minutes = LongArray(4)
            var hrSum = 0; var hrN = 0; var o2Min = 101; var noiseSum = 0; var noiseN = 0; var brSum = 0; var brN = 0
            var gSum = 0; var gN = 0
            for (i in 0 until samples.length()) {
                val p = samples.getJSONObject(i)
                val to = if (i + 1 < samples.length()) samples.getJSONObject(i + 1).optLong("t") else end
                minutes[p.optInt("s").coerceIn(0, 3)] += (to - p.optLong("t")).coerceIn(0, MAX_HOLD_MS) / 60_000
                p.optInt("hr").takeIf { it > 0 }?.let { hrSum += it; hrN++ }
                p.optInt("o2").takeIf { it in 1..100 }?.let { o2Min = minOf(o2Min, it) }
                p.optInt("n").takeIf { it > 0 }?.let { noiseSum += it; noiseN++ }
                p.optInt("br").takeIf { it > 0 }?.let { brSum += it; brN++ }
                p.optInt("g").takeIf { it > 0 }?.let { gSum += it; gN++ }
            }
            val from = Instant.ofEpochMilli(start).atZone(zone)
            val until = Instant.ofEpochMilli(end).atZone(zone)
            buildString {
                append("${from.toLocalDate()} ${from.toLocalTime().withSecond(0).withNano(0)}-${until.toLocalTime().withSecond(0).withNano(0)}")
                append(" in bed ${(end - start) / 60_000} min, asleep ${SessionStore.asleepMinutes(s)} min")
                append(" (awake ${minutes[0]}, light ${minutes[1]}, deep ${minutes[2]}, REM ${minutes[3]} min)")
                if (hrN > 0) append(", avg HR ${hrSum / hrN}")
                if (o2Min <= 100) append(", lowest SpO2 $o2Min%")
                if (brN > 0) append(", breathing ${brSum / brN}/min")
                if (noiseN > 0) append(", noise ${noiseSum / noiseN} dB")
                if (gN > 0) append(", blood sugar avg ${gSum / gN} mg/dL")
                s.optInt("snoozes").takeIf { it > 0 }?.let { append(", $it snoozes (${s.optLong("snoozeSec") / 60} min)") }
                s.optInt("rating").takeIf { it in 1..5 }?.let { append(", rated $it/5") }
            }
        }
        val alarm = PhoneAlarmSync.phoneAlarm(context)?.let { "%02d:%02d".format(it.first, it.second) } ?: "none"
        val window = PhoneAlarmSync.smartWindowMinutes(context)?.let { "$it min" } ?: "unknown"
        val snooze = PhoneAlarmSync.snoozeMinutes(context)?.let { if (it == 0) "off" else "$it min" } ?: "unknown"
        val today = Instant.ofEpochMilli(now).atZone(zone)
        return buildString {
            appendLine("Now: ${today.toLocalDate()} (${today.dayOfWeek}) ${today.toLocalTime().withSecond(0).withNano(0)}")
            appendLine("Current alarm: $alarm, smart wake window: $window, snooze: $snooze")
            appendLine(if (lines.isEmpty()) "No finished sessions yet." else "Latest sessions, oldest first:")
            lines.forEach { appendLine(it) }
        }
    }

    /** Asks the chosen provider, blocking; run it off the main thread. Throws [CoachException] with a message for the user. */
    fun ask(context: Context): Pair<String, CoachAdvice> {
        val provider = CoachPrefs.provider(context)
        val key = CoachPrefs.apiKey(context, provider) ?: throw CoachException(context.getString(R.string.coach_no_key))
        val digest = digest(context)
        if (digest.contains("No finished sessions yet.")) throw CoachException(context.getString(R.string.coach_no_data))
        val text = when (provider) {
            CoachProvider.CLAUDE -> askClaude(context, key, digest)
            CoachProvider.GEMINI -> askGemini(context, key, digest)
        }
        if (text.isBlank()) throw CoachException(context.getString(R.string.coach_empty))
        return text to (runCatching { CoachAdvice.parse(text) }.getOrNull() ?: throw CoachException(context.getString(R.string.coach_unreadable)))
    }

    private fun askClaude(context: Context, key: String, digest: String): String {
        val client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(120)).maxRetries(1).build()
        try {
            val response = client.messages().create(
                MessageCreateParams.builder()
                    .model(CLAUDE_MODEL)
                    .maxTokens(4000L)
                    .system(SYSTEM)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                    .addUserMessage(digest)
                    .build(),
            )
            return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        } catch (e: com.anthropic.errors.UnauthorizedException) {
            throw CoachException(context.getString(R.string.coach_bad_key))
        } catch (e: com.anthropic.errors.RateLimitException) {
            throw CoachException(context.getString(R.string.coach_rate_limited))
        } catch (e: AnthropicException) {
            throw CoachException(context.getString(R.string.coach_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            client.close()
        }
    }

    // Gemini has no SDK in the app: one POST to the REST API, which answers JSON when asked to
    private fun askGemini(context: Context, key: String, digest: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", digest)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 8000))
        val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
        val connection = url.openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val reply = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            when {
                code == 429 -> throw CoachException(context.getString(R.string.coach_rate_limited))
                code == 401 || code == 403 || (code == 400 && reply.contains("API key"))
                -> throw CoachException(context.getString(R.string.coach_bad_key))
                code !in 200..299 -> throw CoachException(
                    context.getString(R.string.coach_failed, runCatching { JSONObject(reply).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")),
                )
            }
            val parts = JSONObject(reply).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            return parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } } ?: ""
        } catch (e: java.io.IOException) {
            throw CoachException(context.getString(R.string.coach_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            connection.disconnect()
        }
    }
}

class CoachException(message: String) : Exception(message)
