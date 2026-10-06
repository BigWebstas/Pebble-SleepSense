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

    // Plain HTTP: GET /alarm -> JSON; anything else -> 404
    private fun handle(client: Socket) = client.use {
        client.soTimeout = 5000
        val request = client.getInputStream().bufferedReader().readLine().orEmpty()
        val ok = request.startsWith("GET /alarm")
        if (ok) PhoneAlarmSync.noteRequest(this)
        val body = if (ok) PhoneAlarmSync.json(this) else "not found"
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
        unregisterReceiver(alarmChanged)
        server?.close()
        super.onDestroy()
    }
}
