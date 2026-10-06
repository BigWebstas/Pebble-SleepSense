package net.webstas.sleepsense

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
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

    // The service is always running, so it sees every change to the next alarm as it happens
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PhoneAlarmSync.noteAlarmChanged(context)
            Log.i(TAG, "next alarm changed")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("bridge", "Alarm bridge", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "bridge")
            .setContentTitle("SleepSense alarm bridge")
            .setContentText("Shares your next phone alarm with the watch app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        ContextCompat.registerReceiver(
            this, alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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
            request.startsWith("GET /alarm") -> {
                PhoneAlarmSync.noteRequest(this)
                "200 OK" to PhoneAlarmSync.json(this)
            }
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
        client.getOutputStream().write(
            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n$body").toByteArray(),
        )
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(alarmChanged)
        server?.close()
        super.onDestroy()
    }
}
