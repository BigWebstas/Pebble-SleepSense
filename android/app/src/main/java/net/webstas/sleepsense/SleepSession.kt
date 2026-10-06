package net.webstas.sleepsense

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Mirrors SleepStage in src/c/model/sleep_types.h. */
object WatchProtocol {
    val APP_UUID: UUID = UUID.fromString("5ea5372b-32c6-4fbe-90e6-f465981b9230")

    // Auto-assigned from package.json `messageKeys` order (see build/js/message_keys.json).
    const val KEY_STATUS_STATE: UInt = 10000u
    const val KEY_TRACKING_ACTIVE: UInt = 10011u
    const val KEY_STATUS_HEART_RATE: UInt = 10014u
}

private const val PREFS = "sleep_state"
private const val KEY_OPEN = "open_session"
private const val KEY_PENDING = "pending_sessions"
private const val MIN_SESSION_MS = 2 * 60_000L

val WRITE_SLEEP = HealthPermission.getWritePermission(SleepSessionRecord::class)
val WRITE_HEART_RATE = HealthPermission.getWritePermission(HeartRateRecord::class)

private const val MIN_HR_GAP_MS = 30_000L

private fun hcStage(watchStage: Int): Int = when (watchStage) {
    1 -> SleepSessionRecord.STAGE_TYPE_LIGHT
    2 -> SleepSessionRecord.STAGE_TYPE_DEEP
    3 -> SleepSessionRecord.STAGE_TYPE_REM
    else -> SleepSessionRecord.STAGE_TYPE_AWAKE
}

/**
 * Collects stage changes for the session the watch is tracking, persisted in SharedPreferences
 * so a killed process doesn't lose a night. Finished sessions queue up until Health Connect
 * accepts them.
 */
object SleepRecorder {
    private val flushLock = Mutex()

    @Synchronized
    fun onWatchUpdate(context: Context, tracking: Boolean, stage: Int?, heartRate: Int?, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val open = prefs.getString(KEY_OPEN, null)?.let(::JSONObject)

        if (!tracking) {
            if (open != null) {
                open.put("end", now)
                queue(prefs, open)
                prefs.edit { remove(KEY_OPEN) }
            }
            return
        }

        val session = open ?: JSONObject().put("start", now).put("changes", JSONArray()).put("hr", JSONArray())
        val changes = session.getJSONArray("changes")
        if (stage != null) {
            val last = changes.optJSONObject(changes.length() - 1)
            if (last == null || last.getInt("stage") != stage) {
                changes.put(JSONObject().put("at", now).put("stage", stage))
            }
        }
        if (heartRate != null && heartRate > 0) {
            val hr = session.optJSONArray("hr") ?: JSONArray().also { session.put("hr", it) }
            val last = hr.optJSONObject(hr.length() - 1)
            if (last == null || now - last.getLong("at") >= MIN_HR_GAP_MS) {
                hr.put(JSONObject().put("at", now).put("bpm", heartRate))
            }
        }
        prefs.edit { putString(KEY_OPEN, session.toString()) }
    }

    private fun queue(prefs: android.content.SharedPreferences, session: JSONObject) {
        val pending = JSONArray(prefs.getString(KEY_PENDING, "[]")).put(session)
        prefs.edit { putString(KEY_PENDING, pending.toString()) }
    }

    fun pendingCount(context: Context): Int =
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, "[]")).length()

    /** Writes queued sessions to Health Connect; returns how many remain queued. */
    suspend fun flush(context: Context): Int = flushLock.withLock { doFlush(context) }

    private suspend fun doFlush(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = JSONArray(prefs.getString(KEY_PENDING, "[]"))
        if (pending.length() == 0) return 0

        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return pending.length()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (WRITE_SLEEP !in granted) return pending.length()
        val writeHr = WRITE_HEART_RATE in granted

        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val session = pending.getJSONObject(i)
            val sleep = toRecord(session)
            if (sleep == null) continue // too short or empty: drop
            val records = listOfNotNull(sleep, if (writeHr) toHeartRate(session) else null)
            try {
                client.insertRecords(records)
            } catch (e: Exception) {
                remaining.put(session)
            }
        }
        prefs.edit { putString(KEY_PENDING, remaining.toString()) }
        return remaining.length()
    }

    private fun toHeartRate(session: JSONObject): HeartRateRecord? {
        val hr = session.optJSONArray("hr") ?: return null
        val samples = (0 until hr.length()).map {
            val s = hr.getJSONObject(it)
            HeartRateRecord.Sample(Instant.ofEpochMilli(s.getLong("at")), s.getLong("bpm"))
        }
        if (samples.isEmpty()) return null
        val start = Instant.ofEpochMilli(session.getLong("start"))
        // Health Connect needs end > start and every sample inside the record.
        val end = maxOf(Instant.ofEpochMilli(session.getLong("end")), samples.last().time.plusMillis(1))
        val zone = ZoneId.systemDefault().rules
        return HeartRateRecord(
            startTime = start,
            startZoneOffset = zone.getOffset(start),
            endTime = end,
            endZoneOffset = zone.getOffset(end),
            samples = samples,
            metadata = Metadata.manualEntry(),
        )
    }

    private fun toRecord(session: JSONObject): SleepSessionRecord? {
        val start = session.getLong("start")
        val end = session.getLong("end")
        if (end - start < MIN_SESSION_MS) return null

        val changes = session.getJSONArray("changes")
        val stages = (0 until changes.length()).map { i ->
            val c = changes.getJSONObject(i)
            val from = c.getLong("at")
            val to = if (i + 1 < changes.length()) changes.getJSONObject(i + 1).getLong("at") else end
            SleepSessionRecord.Stage(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to), hcStage(c.getInt("stage")))
        }.filter { it.endTime > it.startTime }

        val zone = ZoneId.systemDefault().rules
        val startI = Instant.ofEpochMilli(start)
        val endI = Instant.ofEpochMilli(end)
        return SleepSessionRecord(
            startTime = startI,
            startZoneOffset = zone.getOffset(startI),
            endTime = endI,
            endZoneOffset = zone.getOffset(endI),
            title = "SleepSense",
            stages = stages,
            metadata = Metadata.manualEntry(),
        )
    }
}
