package net.webstas.sleepsense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "AlarmBridgeService"
const val ALARM_BRIDGE_PORT = 8765

/**
 * Serves the phone's next alarm to the SleepSense watchapp's phone-side JavaScript, which runs
 * inside the Pebble app and can reach 127.0.0.1 but not Android APIs. Bound to the loopback
 * address only, so nothing off the phone can connect. Kept running as a quiet foreground service.
 */
class AlarmBridgeService : Service() {
    private var server: ServerSocket? = null

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

    // Plain HTTP: GET /alarm -> JSON; anything else -> 404
    private fun handle(client: Socket) = client.use {
        client.soTimeout = 5000
        val request = client.getInputStream().bufferedReader().readLine().orEmpty()
        val ok = request.startsWith("GET /alarm") || request.startsWith("GET /disable") || request.startsWith("GET /enable")
        val body = when {
            request.startsWith("GET /enable") -> {
                // GET /enable?hour=6&min=15
                val hour = Regex("hour=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
                val min = Regex("min=(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
                if (hour == null || min == null || hour > 23 || min > 59) """{"result":"bad request"}"""
                else """{"result":"${PhoneAlarmSync.enablePhoneAlarm(this, hour, min)}"}"""
            }
            request.startsWith("GET /disable") -> """{"result":"${PhoneAlarmSync.disablePhoneAlarm(this)}"}"""
            request.startsWith("GET /alarm") -> {
                PhoneAlarmSync.noteRequest(this)
                PhoneAlarmSync.json(this)
            }
            else -> "not found"
        }
        Log.i(TAG, "$request -> ${if (ok) body else 404}")
        val status = if (ok) "200 OK" else "404 Not Found"
        client.getOutputStream().write(
            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n$body").toByteArray(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.close()
        super.onDestroy()
    }
}
