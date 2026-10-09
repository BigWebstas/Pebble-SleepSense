package net.webstas.sleepsense

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "AlarmBridgeService"
const val ALARM_BRIDGE_PORT = 8765
private const val MAX_BODY = 5 * 1024 * 1024 // a month of history is ~150 KB

/**
 * Serves the phone's next alarm to the SleepSense watchapp's phone-side JavaScript, which runs
 * inside the Pebble app and can reach 127.0.0.1 but not Android APIs. Bound to the loopback
 * address only, so nothing off the phone can connect. Kept running as a quiet foreground service.
 */
class AlarmBridgeService : Service() {
    private var server: ServerSocket? = null
    private val monitor by lazy { NoiseMonitor(applicationContext) }

    // The service is always running, so it sees every change to the next alarm as it happens
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PhoneAlarmSync.noteAlarmChanged(context)
            SleepWidgetProvider.refreshAll(context)
            Log.i(TAG, "next alarm changed")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("bridge", "Alarm bridge", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        ContextCompat.registerReceiver(
            this, alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        HomeAssistant.start(this)

        thread(name = "alarm-bridge", isDaemon = true) {
            try {
                val s = ServerSocket(ALARM_BRIDGE_PORT, 8, InetAddress.getByName("127.0.0.1"))
                server = s
                while (!s.isClosed) {
                    val client = s.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (e: Exception) {
                if (server?.isClosed != true) Log.w(TAG, "bridge stopped: $e")
            }
        }
    }

    // Plain HTTP on loopback: GET /alarm -> next alarm JSON; POST /sessions -> store the history.
    private fun handle(client: Socket) = client.use {
        client.soTimeout = 5000
        val input = client.getInputStream().buffered()
        val request = readLine(input)
        var contentLength = 0
        while (true) {
            val header = readLine(input)
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val (status, body) = when {
            request.startsWith("GET /command") -> "200 OK" to """{"cmd":"${Commands.await(25_000)}"}"""
            request.startsWith("GET /alarm") -> {
                PhoneAlarmSync.noteRequest(this)
                // The watchapp's JS checks in every minute and says whether the watch is tracking
                val tracking = Regex("tracking=(\\d)").find(request)?.groupValues?.get(1)
                if (tracking != null) {
                    val since = Regex("since=(\\d+)").find(request)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val stage = Regex("stage=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
                    val hr = Regex("hr=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
                    val duration = Regex("duration=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
                    val isTracking = tracking == "1"
                    val wasTracking = WidgetState.tracking(this)
                    WidgetState.note(this, isTracking, since)
                    if (isTracking && !wasTracking) {
                        onTrackingStarted()
                    } else if (!isTracking && wasTracking) {
                        onTrackingStopped()
                    }
                    // Asked to start but the watch checks in still idle (e.g. its app had just been
                    // restarted and the command was lost): ask again
                    if (tracking == "0" && WidgetState.startRequestedRecently(this)) {
                        Commands.queueStart()
                    } else {
                        HomeAssistant.publishState(isTracking, stage, hr, duration)
                    }
                    SleepRecorder.onWatchUpdate(this, isTracking, stage, hr)
                    if (!isTracking && wasTracking) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val left = SleepRecorder.flush(this@AlarmBridgeService)
                            Log.i(TAG, "Session ended; $left queued for Health Connect")
                        }
                    }
                }
                SleepWidgetProvider.refreshAll(this)
                "200 OK" to PhoneAlarmSync.json(this)
            }
            request.startsWith("GET /noise/start") -> "200 OK" to noiseStart()
            request.startsWith("GET /noise/stop") -> "200 OK" to noiseStop()
            request.startsWith("GET /noise") -> "200 OK" to noiseStatus()
            request.startsWith("POST /sessions") && contentLength in 1..MAX_BODY -> {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                if (read == contentLength) {
                    SessionStore.save(this, String(bytes, Charsets.UTF_8))
                    "200 OK" to """{"ok":true,"bytes":$read}"""
                } else {
                    "400 Bad Request" to "incomplete body"
                }
            }
            else -> "404 Not Found" to "not found"
        }
        Log.i(TAG, "$request -> $status")
        try {
            client.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$body").toByteArray(),
            )
        } catch (e: java.io.IOException) {
            // The caller went away: a command it was handed must not be lost
            if (body.contains("\"start\"")) Commands.queueStart()
            if (body.contains("\"stop\"")) Commands.queueStop()
        }
    }

    // One header line without its CRLF (bytes read raw so a body is never swallowed by a text reader)
    private fun readLine(input: java.io.InputStream): String {
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.write(c)
        }
        return line.toString(Charsets.UTF_8.name())
    }

    private fun notification(listening: Boolean): Notification =
        Notification.Builder(this, "bridge")
            .setContentTitle(if (listening) "SleepSense is listening for noise" else "SleepSense alarm bridge")
            .setContentText(if (listening) "Recording a clip if the room gets suddenly loud" else "Shares your next phone alarm with the watch app")
            .setSmallIcon(if (listening) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

    // The microphone type is added only while listening, to the service that is already in the foreground
    private fun setListening(on: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(1, notification(on), types)
    }

    private fun noiseStart(): String {
        if (!NoiseClips.isEnabled(this)) return """{"ok":false,"error":"noise monitoring is off in the app"}"""
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return """{"ok":false,"error":"microphone permission not granted"}"""
        }
        return try {
            setListening(true)
            monitor.start()
            """{"ok":true}"""
        } catch (e: Exception) {
            Log.w(TAG, "could not start listening: $e")
            setListening(false)
            """{"ok":false,"error":"${e.javaClass.simpleName}"}"""
        }
    }

    private fun noiseStop(): String {
        monitor.stop()
        setListening(false)
        return """{"ok":true}"""
    }

    private fun noiseStatus() =
        """{"listening":${monitor.listening},"db":${monitor.lastDb},"avg":${monitor.avg60},"clips":${NoiseClips.list(this).size}}"""

    private fun onTrackingStarted() {
        if (WhiteNoisePrefs.isPlayWhileTracking(this)) {
            WhiteNoisePlayer.start(this)
            monitor.resetBaseline()
        }
    }

    private fun onTrackingStopped() {
        if (WhiteNoisePlayer.isPlaying) {
            WhiteNoisePlayer.stop()
            monitor.resetBaseline()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WhiteNoisePlayer.stop()
        monitor.stop()
        unregisterReceiver(alarmChanged)
        server?.close()
        super.onDestroy()
    }
}
