package net.webstas.sleepsense

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "HomeAssistant"
private const val BASE = "sleepsense"
private const val AVAILABILITY = "$BASE/availability"
private const val TRACKING = "$BASE/tracking"
private const val TRACKING_SET = "$BASE/tracking/set"
private const val STAGE = "$BASE/stage"
private const val HEART_RATE = "$BASE/heart_rate"
private const val SLEEP_DURATION = "$BASE/sleep_duration"
private const val ALARM_TIME = "$BASE/alarm_time"
private const val ALARM_TIME_SET = "$BASE/alarm_time/set"
private const val ALARM_ENABLED = "$BASE/alarm_enabled"
private const val ALARM_ENABLED_SET = "$BASE/alarm_enabled/set"
private const val RETRY_MS = 30_000L

data class MqttSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val user: String = "",
    val password: String = "",
) {
    companion object {
        private const val PREFS = "home_assistant"

        fun load(context: Context): MqttSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return MqttSettings(
                p.getBoolean("enabled", false), p.getString("host", "")!!, p.getInt("port", 1883),
                p.getString("user", "")!!, p.getString("password", "")!!,
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", enabled).putString("host", host).putInt("port", port)
                .putString("user", user).putString("password", password)
        }
    }
}

/**
 * Publishes the watch's tracking state, sleep stage, heart rate and time asleep to an MQTT broker, with Home
 * Assistant MQTT discovery so the entities appear on their own, and lets Home Assistant start and
 * stop tracking. Runs inside the always-on [AlarmBridgeService] process. Port 8883 uses TLS.
 */
object HomeAssistant {
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-io").apply { isDaemon = true } }
    private var client: MqttClient? = null
    private var generation = 0

    private var appContext: Context? = null

    // Last values, republished whenever the connection is (re)established
    private var tracking = false
    private var stage = "idle"
    private var heartRate: Int? = null
    private var asleepMs = 0L // light + deep + REM this session; kept after tracking stops
    private var lastUpdateAt = 0L

    init {
        io.scheduleWithFixedDelay({
            synchronized(this) {
                if (tracking && client?.isConnected == true) {
                    val now = System.currentTimeMillis()
                    if (stage in ASLEEP) asleepMs += now - lastUpdateAt
                    lastUpdateAt = now
                    client?.let { publishCurrent(it) }
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    val connected get() = synchronized(this) { client?.isConnected == true }

    /** The one-line state shown under the settings button and on the settings screen. */
    fun statusText(context: Context): Int = when {
        !MqttSettings.load(context).enabled -> R.string.ha_off
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED ->
            R.string.ha_needs_permission
        connected -> R.string.ha_connected
        else -> R.string.ha_disconnected
    }

    /** (Re)connects with the saved settings, or just disconnects when the integration is off. */
    @Synchronized
    fun start(context: Context) {
        val settings = MqttSettings.load(context)
        val app = context.applicationContext
        appContext = app
        synchronized(this) {
            if (WidgetState.tracking(app) || WidgetState.startRequestedRecently(app)) {
                tracking = true
            }
        }
        val old = client
        client = null
        val mine = ++generation
        thread(name = "mqtt-connect", isDaemon = true) {
            old?.let { close(it) }
            if (settings.enabled && settings.host.isNotBlank()) connect(app, settings, mine)
        }
    }

    fun publishState(tracking: Boolean, stage: Int? = null, heartRate: Int? = null, durationMinutes: Int? = null) {
        io.execute {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (this.tracking && this.stage in ASLEEP) asleepMs += now - lastUpdateAt
                if (tracking && !this.tracking) asleepMs = 0
                lastUpdateAt = now
                if (durationMinutes != null && durationMinutes > 0) {
                    asleepMs = maxOf(asleepMs, durationMinutes * 60_000L)
                }
                this.tracking = tracking
                this.stage = if (tracking) stage?.let(::stageName) ?: if (this.stage == "idle") "awake" else this.stage else "idle"
                this.heartRate = if (tracking) heartRate?.takeIf { it > 0 } ?: this.heartRate else null
                client?.takeIf { it.isConnected }?.let { publishCurrent(it) }
            }
        }
    }

    fun publishAlarmTime(context: Context? = null) {
        val app = (context ?: appContext)?.applicationContext ?: return
        io.execute {
            synchronized(this) {
                client?.takeIf { it.isConnected }?.let { c ->
                    val alarm = PhoneAlarmSync.phoneAlarm(app) ?: PhoneAlarmSync.customAlarm(app) ?: (7 to 0)
                    try {
                        c.publish(ALARM_TIME, retained("%02d:%02d".format(alarm.first, alarm.second)))
                    } catch (e: Exception) {
                        Log.w(TAG, "could not publish alarm time: $e")
                    }
                }
            }
        }
    }

    fun publishAlarmEnabled(context: Context? = null) {
        val app = (context ?: appContext)?.applicationContext ?: return
        io.execute {
            synchronized(this) {
                client?.takeIf { it.isConnected }?.let { c ->
                    try {
                        c.publish(ALARM_ENABLED, retained(if (PhoneAlarmSync.isAlarmEnabled(app)) "ON" else "OFF"))
                    } catch (e: Exception) {
                        Log.w(TAG, "could not publish alarm enabled: $e")
                    }
                }
            }
        }
    }

    private val ASLEEP = setOf("light", "deep", "rem")

    private fun stageName(watchStage: Int) = when (watchStage) {
        1 -> "light"
        2 -> "deep"
        3 -> "rem"
        else -> "awake"
    }

    private fun connect(app: Context, settings: MqttSettings, mine: Int) {
        val scheme = if (settings.port == 8883) "ssl" else "tcp"
        val c = MqttClient("$scheme://${settings.host}:${settings.port}", MqttClient.generateClientId(), MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                io.execute { synchronized(this@HomeAssistant) { onConnected(c) } }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost: $cause")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                when (topic) {
                    TRACKING_SET -> {
                        when (String(message.payload).trim().uppercase()) {
                            "ON" -> {
                                publishState(true)
                                TrackingControl.start(app)
                            }
                            "OFF" -> {
                                publishState(false)
                                TrackingControl.stop(app)
                            }
                        }
                    }
                    ALARM_TIME_SET -> {
                        val payload = String(message.payload).trim().trim('"', '\'')
                        val match = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?""").find(payload)
                        if (match != null) {
                            val h = match.groupValues[1].toInt().coerceIn(0, 23)
                            val m = match.groupValues[2].toInt().coerceIn(0, 59)
                            PhoneAlarmSync.setEnabled(app, false)
                            PhoneAlarmSync.setCustomAlarm(app, h, m)
                            PhoneAlarmSync.setAlarmEnabled(app, true)
                            Commands.queueSync()
                            SleepWidgetProvider.refreshAll(app)
                            try {
                                c.publish(ALARM_TIME, retained("%02d:%02d".format(h, m)))
                                c.publish(ALARM_ENABLED, retained("ON"))
                            } catch (e: Exception) {
                                Log.w(TAG, "could not publish alarm time: $e")
                            }
                        }
                    }
                    ALARM_ENABLED_SET -> {
                        when (String(message.payload).trim().uppercase()) {
                            "ON" -> {
                                PhoneAlarmSync.setAlarmEnabled(app, true)
                                SleepWidgetProvider.refreshAll(app)
                                try {
                                    c.publish(ALARM_ENABLED, retained("ON"))
                                } catch (e: Exception) {
                                    Log.w(TAG, "could not publish alarm enabled: $e")
                                }
                            }
                            "OFF" -> {
                                PhoneAlarmSync.setAlarmEnabled(app, false)
                                SleepWidgetProvider.refreshAll(app)
                                try {
                                    c.publish(ALARM_ENABLED, retained("OFF"))
                                } catch (e: Exception) {
                                    Log.w(TAG, "could not publish alarm enabled: $e")
                                }
                            }
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            if (settings.user.isNotEmpty()) {
                userName = settings.user
                password = settings.password.toCharArray()
            }
            setWill(AVAILABILITY, "offline".toByteArray(), 1, true)
        }
        while (true) {
            synchronized(this) {
                if (generation != mine) return
                client = c
            }
            try {
                c.connect(options) // automatic reconnect only applies after the first success
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not connect: $e")
                Thread.sleep(RETRY_MS)
            }
        }
    }

    private fun onConnected(c: MqttClient) {
        try {
            c.subscribe(TRACKING_SET, 1)
            c.subscribe(ALARM_TIME_SET, 1)
            c.subscribe(ALARM_ENABLED_SET, 1)
            publishDiscovery(c)
            publishCurrent(c)
            c.publish(AVAILABILITY, retained("online"))
        } catch (e: Exception) {
            Log.w(TAG, "could not publish: $e")
        }
    }

    private fun publishCurrent(c: MqttClient) {
        try {
            c.publish(TRACKING, retained(if (tracking) "ON" else "OFF"))
            c.publish(STAGE, retained(stage))
            c.publish(HEART_RATE, retained(heartRate?.takeIf { it > 0 }?.toString() ?: "0"))
            c.publish(SLEEP_DURATION, retained((asleepMs / 60_000).toString()))
            appContext?.let { app ->
                val alarm = PhoneAlarmSync.phoneAlarm(app) ?: PhoneAlarmSync.customAlarm(app) ?: (7 to 0)
                c.publish(ALARM_TIME, retained("%02d:%02d".format(alarm.first, alarm.second)))
                c.publish(ALARM_ENABLED, retained(if (PhoneAlarmSync.isAlarmEnabled(app)) "ON" else "OFF"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not publish: $e")
        }
    }

    // Home Assistant MQTT discovery: one device with a tracking switch and three sensors
    private fun publishDiscovery(c: MqttClient) {
        val device = JSONObject().put("identifiers", JSONArray().put("sleepsense")).put("name", "SleepSense")
        fun entity(component: String, id: String, name: String, extra: JSONObject.() -> Unit) {
            val config = JSONObject()
                .put("name", name)
                .put("unique_id", "sleepsense_$id")
                .put("state_topic", "$BASE/$id")
                .put("availability_topic", AVAILABILITY)
                .put("device", device)
                .apply(extra)
            c.publish("homeassistant/$component/sleepsense/$id/config", retained(config.toString()))
        }
        entity("switch", "tracking", "Sleep tracking") {
            put("command_topic", TRACKING_SET)
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("state_on", "ON")
            put("state_off", "OFF")
            put("icon", "mdi:sleep")
        }
        entity("sensor", "stage", "Sleep stage") { put("icon", "mdi:sleep") }
        entity("sensor", "heart_rate", "Heart rate") {
            put("unit_of_measurement", "bpm")
            put("state_class", "measurement")
            put("icon", "mdi:heart-pulse")
        }
        entity("sensor", "sleep_duration", "Sleep duration") {
            put("device_class", "duration")
            put("unit_of_measurement", "min")
            put("state_class", "measurement")
        }
        // Clear unsupported MQTT 'time' discovery topic if previously retained
        c.publish("homeassistant/time/sleepsense/alarm_time/config", retained(""))
        entity("text", "alarm_time", "Watch alarm time") {
            put("command_topic", ALARM_TIME_SET)
            put("pattern", "^([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$")
            put("mode", "text")
            put("icon", "mdi:alarm")
        }
        entity("switch", "alarm_enabled", "Watch alarm enabled") {
            put("command_topic", ALARM_ENABLED_SET)
            put("payload_on", "ON")
            put("payload_off", "OFF")
            put("state_on", "ON")
            put("state_off", "OFF")
            put("icon", "mdi:alarm-check")
        }
    }

    private fun retained(payload: String) = MqttMessage(payload.toByteArray()).apply {
        qos = 1
        isRetained = true
    }

    private fun close(c: MqttClient) {
        try {
            if (c.isConnected) {
                c.publish(AVAILABILITY, retained("offline"))
                c.disconnect(2000)
            }
            c.close()
        } catch (e: Exception) {
            runCatching { c.disconnectForcibly(0, 0) }
        }
    }
}
