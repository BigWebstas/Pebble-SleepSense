package net.webstas.sleepsense

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.WindowInsets
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshTracking()
            handler.postDelayed(this, 3000) // the watch checks in about once a minute and on every change
        }
    }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNoise() }
    private val requestHealth =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refreshHealth() }

    private fun <T : android.view.View> id(res: Int): T = findViewById(res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, AlarmBridgeService::class.java))
        setContentView(R.layout.activity_main)

        // The app draws edge to edge: keep content clear of the status and navigation bars
        id<android.view.View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        id<Button>(R.id.tracking_button).setOnClickListener {
            if (WidgetState.tracking(this)) TrackingControl.stop(this) else TrackingControl.start(this)
            refreshTracking()
        }
        id<Button>(R.id.clock_button).setOnClickListener { openClock() }

        id<Switch>(R.id.alarm_sync_switch).apply {
            isChecked = PhoneAlarmSync.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                PhoneAlarmSync.setEnabled(this@MainActivity, on)
                refreshTracking()
            }
        }
        id<Switch>(R.id.noise_switch).apply {
            isChecked = NoiseClips.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                NoiseClips.setEnabled(this@MainActivity, on)
                if (on && !micGranted()) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                refreshNoise()
            }
        }
        id<Button>(R.id.clips_button).setOnClickListener { startActivity(Intent(this, ClipsActivity::class.java)) }
        id<Button>(R.id.history_button).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        id<Button>(R.id.grant_hc_button).setOnClickListener { requestHealth.launch(setOf(WRITE_SLEEP, WRITE_HEART_RATE)) }
        id<Button>(R.id.widget_button).setOnClickListener {
            val manager = getSystemService(AppWidgetManager::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, SleepWidgetProvider::class.java), null, null)
            }
        }
        id<TextView>(R.id.version_text).text =
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)
    }

    override fun onResume() {
        super.onResume()
        refreshNoise()
        refreshHealth()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun clock(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    // Tracking status, the next alarm, and what the main button will do
    private fun refreshTracking() {
        val tracking = WidgetState.tracking(this)
        val (title, subtitle) = WidgetState.lines(this)
        id<TextView>(R.id.tracking_status_text).text = title
        id<TextView>(R.id.alarm_status_text).text = PhoneAlarmSync.describe(this).lineSequence().first()
        id<Button>(R.id.tracking_button).setText(if (tracking) R.string.stop_tracking_button else R.string.start_tracking_button)
    }

    // Opens the Clock app's alarm list: the standard Clock app if there are several that can
    private fun openClock() {
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val handlers = packageManager.queryIntentActivities(show, 0).map { it.activityInfo.packageName }.distinct()
        val target = handlers.singleOrNull()
            ?: handlers.firstOrNull { it == "com.google.android.deskclock" || it == "com.android.deskclock" }
        target?.let { show.setPackage(it) }
        if (handlers.isEmpty()) {
            Toast.makeText(this, R.string.open_clock_missing, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(show)
        }
    }

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshNoise() {
        val clips = NoiseClips.list(this).size
        id<TextView>(R.id.noise_status_text).text = when {
            !NoiseClips.isEnabled(this) -> getString(R.string.noise_off)
            !micGranted() -> getString(R.string.noise_needs_permission)
            else -> getString(R.string.noise_listening, clips)
        }
    }

    private fun refreshHealth() = lifecycleScope.launch {
        val status = id<TextView>(R.id.hc_status_text)
        val grant = id<Button>(R.id.grant_hc_button)
        if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) {
            status.setText(R.string.hc_unavailable)
            grant.isEnabled = false
            return@launch
        }
        val granted = HealthConnectClient.getOrCreate(this@MainActivity)
            .permissionController.getGrantedPermissions().contains(WRITE_SLEEP)
        val left = if (granted) SleepRecorder.flush(this@MainActivity) else SleepRecorder.pendingCount(this@MainActivity)
        status.text = getString(if (granted) R.string.hc_granted else R.string.hc_needed, left)
    }
}
