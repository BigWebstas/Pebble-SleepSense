package net.webstas.sleepsense

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var alarmStatus: TextView

    private val requestPermission =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val grant = Button(this).apply {
            text = "Grant Health Connect access"
            setOnClickListener { requestPermission.launch(setOf(WRITE_SLEEP, WRITE_HEART_RATE)) }
        }
        alarmStatus = TextView(this).apply { textSize = 16f; setPadding(0, 16, 0, 16) }
        val alarmSwitch = Switch(this).apply {
            text = "Sync phone alarm to Pebble"
            isChecked = PhoneAlarmSync.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                PhoneAlarmSync.setEnabled(this@MainActivity, on)
                refreshAlarm()
            }
        }
        val syncNow = Button(this).apply {
            text = "Sync alarm now"
            setOnClickListener {
                lifecycleScope.launch {
                    val sender = DefaultPebbleSender(this@MainActivity)
                    val result = try {
                        var r = PhoneAlarmSync.sync(this@MainActivity, sender, null, null, force = true)
                        if (r == SyncResult.WATCH_APP_CLOSED) {
                            // You tapped the button, so opening SleepSense on the watch is expected
                            PhoneAlarmSync.openWatchApp(sender)
                            delay(4000)
                            r = PhoneAlarmSync.sync(this@MainActivity, sender, null, null, force = true)
                        }
                        r
                    } finally {
                        sender.close()
                    }
                    alarmStatus.text = PhoneAlarmSync.describe(this@MainActivity) + "\n" + result.message
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(grant)
            addView(alarmSwitch)
            addView(alarmStatus)
            addView(syncNow)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshAlarm()
    }

    private fun refreshAlarm() {
        alarmStatus.text = PhoneAlarmSync.describe(this)
    }

    private fun refresh() = lifecycleScope.launch {
        if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) {
            status.text = "Health Connect is not available on this phone."
            return@launch
        }
        val granted = HealthConnectClient.getOrCreate(this@MainActivity)
            .permissionController.getGrantedPermissions().contains(WRITE_SLEEP)
        val left = if (granted) SleepRecorder.flush(this@MainActivity) else SleepRecorder.pendingCount(this@MainActivity)
        status.text = (if (granted) "Health Connect: access granted" else "Health Connect: access needed") +
            "\nSessions waiting to sync: $left" +
            "\n\nTrack sleep from the SleepSense watch app; sessions sync when you stop tracking."
    }
}
