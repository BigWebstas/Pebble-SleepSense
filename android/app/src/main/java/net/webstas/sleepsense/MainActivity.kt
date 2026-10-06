package net.webstas.sleepsense

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var alarmStatus: TextView
    private lateinit var overlayStatus: TextView

    private val requestPermission =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, AlarmBridgeService::class.java))
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
        val disableSwitch = Switch(this).apply {
            text = "Turn the phone alarm off/on with the Pebble alarm (switches off the whole alarm in Clock, even a repeating one; turning on adds a one-time alarm)"
            isChecked = PhoneAlarmSync.isDisableOnWatchOff(this@MainActivity)
            setOnCheckedChangeListener { _, on -> PhoneAlarmSync.setDisableOnWatchOff(this@MainActivity, on) }
        }
        overlayStatus = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 8) }
        val overlayButton = Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(grant)
            addView(alarmSwitch)
            addView(alarmStatus)
            addView(disableSwitch)
            addView(overlayStatus)
            addView(overlayButton)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshAlarm()
    }

    private fun refreshAlarm() {
        alarmStatus.text = PhoneAlarmSync.describe(this)
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "Display over other apps: allowed, so the phone alarm is turned off automatically."
        else "Display over other apps: not allowed, so you'll get a notification to tap instead."
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
