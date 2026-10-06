package net.webstas.sleepsense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var alarmStatus: TextView
    private lateinit var noiseStatus: TextView

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNoise() }

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
        noiseStatus = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 8) }
        val noiseSwitch = Switch(this).apply {
            text = "Record clips on noise spikes while tracking"
            isChecked = NoiseClips.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                NoiseClips.setEnabled(this@MainActivity, on)
                if (on && !micGranted()) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                refreshNoise()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The app draws edge to edge: keep content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(48 + bars.left, 48 + bars.top, 48 + bars.right, 48 + bars.bottom)
                insets
            }
            addView(status)
            addView(grant)
            addView(alarmSwitch)
            addView(alarmStatus)
            addView(noiseSwitch)
            addView(noiseStatus)
            addView(Button(this@MainActivity).apply {
                text = "Noise clips"
                setOnClickListener { startActivity(Intent(this@MainActivity, ClipsActivity::class.java)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Sleep history and export"
                setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshAlarm()
        refreshNoise()
    }

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshNoise() {
        val clips = NoiseClips.list(this).size
        noiseStatus.text = when {
            !NoiseClips.isEnabled(this) -> "Noise monitoring is off. It listens through the phone microphone only while the watch is tracking sleep."
            !micGranted() -> "Microphone permission is needed. Switch this off and on to be asked again."
            else -> "Listening while you track sleep. $clips clip" + (if (clips == 1) "" else "s") + " saved."
        }
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
