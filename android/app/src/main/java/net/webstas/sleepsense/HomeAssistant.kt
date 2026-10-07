package net.webstas.sleepsense

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "HomeAssistant"
private const val BASE = "sleepsense"
private const val AVAILABILITY = "$BASE/availability"
private const val TRACKING = "$BASE/tracking"
private const val TRACKING_SET = "$BASE/tracking/set"
private const val STAGE = "$BASE/stage"
private const val HEART_RATE = "$BASE/heart_rate"
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
 * Publishes the watch's tracking state, sleep stage and heart rate to an MQTT broker, with Home
 * Assistant MQTT discovery so the entities appear on their own, and lets Home Assistant start and
 * stop tracking. Runs inside the always-on [AlarmBridgeService] process. Port 8883 uses TLS.
 */
object HomeAssistant {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mqtt-io").apply { isDaemon = true } }
    private var client: MqttClient? = null
    private var generation = 0

    // Last values, republished whenever the connection is (re)established
    private var tracking = false
    private var stage = "idle"
    private var heartRate: Int? = null

    val connected get() = synchronized(this) { client?.isConnected == true }

    /** (Re)connects with the saved settings, or just disconnects when the integration is off. */
    @Synchronized
    fun start(context: Context) {
        val settings = MqttSettings.load(context)
        val app = context.applicationContext
        val old = client
        client = null
        val mine = ++generation
        thread(name = "mqtt-connect", isDaemon = true) {
            old?.let { close(it) }
            if (settings.enabled && settings.host.isNotBlank()) connect(app, settings, mine)
        }
    }

    fun publishState(tracking: Boolean, stage: Int?, heartRate: Int?) {
        io.execute {
            synchronized(this) {
                this.tracking = tracking
                this.stage = if (tracking) stage?.let(::stageName) ?: this.stage else "idle"
                this.heartRate = if (tracking) heartRate?.takeIf { it > 0 } ?: this.heartRate else null
                client?.takeIf { it.isConnected }?.let { publishCurrent(it) }
            }
        }
    }

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
                when (String(message.payload).trim().uppercase()) {
                    "ON" -> TrackingControl.start(app)
                    "OFF" -> TrackingControl.stop(app)
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
            c.publish(HEART_RATE, retained(heartRate?.toString() ?: "None"))
        } catch (e: Exception) {
            Log.w(TAG, "could not publish: $e")
        }
    }

    // Home Assistant MQTT discovery: one device with a tracking switch and two sensors
    private fun publishDiscovery(c: MqttClient) {
        val device = JSONObject().put("identifiers", listOf("sleepsense")).put("name", "SleepSense")
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
        entity("switch", "tracking", "Sleep tracking") { put("command_topic", TRACKING_SET) }
        entity("sensor", "stage", "Sleep stage") { put("icon", "mdi:sleep") }
        entity("sensor", "heart_rate", "Heart rate") {
            put("unit_of_measurement", "bpm")
            put("state_class", "measurement")
            put("icon", "mdi:heart-pulse")
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
