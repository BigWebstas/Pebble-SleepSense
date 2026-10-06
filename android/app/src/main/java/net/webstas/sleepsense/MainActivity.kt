package net.webstas.sleepsense

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    private var update: UpdateResult? = null

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

        id<Button>(R.id.clock_button).setIcon(R.drawable.ic_alarm)
        id<Button>(R.id.clips_button).setIcon(R.drawable.ic_mic)
        id<Button>(R.id.history_button).setIcon(R.drawable.ic_history)
        id<Button>(R.id.grant_hc_button).setIcon(R.drawable.ic_favorite)
        id<Button>(R.id.widget_button).setIcon(R.drawable.ic_widgets)
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
        id<Button>(R.id.install_watch_button).apply {
            setIcon(R.drawable.ic_watch)
            setOnClickListener { installWatchApp() }
        }
        id<Button>(R.id.update_button).apply {
            setOnClickListener {
                val found = update
                if (found is UpdateResult.Available) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found.pageUrl)))
                else checkForUpdate()
            }
        }
        if (UpdateChecker.dueForAutoCheck(this)) checkForUpdate() else refreshUpdate()
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
        id<Button>(R.id.tracking_button).apply {
            setText(if (tracking) R.string.stop_tracking_button else R.string.start_tracking_button)
            setIcon(if (tracking) R.drawable.ic_stop else R.drawable.ic_play_arrow)
        }
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

    private fun installedVersion() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    private fun checkForUpdate() {
        id<TextView>(R.id.update_status_text).setText(R.string.update_checking)
        lifecycleScope.launch {
            update = withContext(Dispatchers.IO) { UpdateChecker.check(this@MainActivity, installedVersion()) }
            refreshUpdate()
        }
    }

    private fun refreshUpdate() {
        val status = id<TextView>(R.id.update_status_text)
        val button = id<Button>(R.id.update_button)
        val found = update
        status.text = when (found) {
            is UpdateResult.Available -> getString(R.string.update_available, found.version, installedVersion())
            is UpdateResult.UpToDate -> getString(R.string.update_current, found.version)
            UpdateResult.NoReleases -> getString(R.string.update_none_published)
            UpdateResult.Failed -> getString(R.string.update_failed)
            null -> getString(R.string.version_label, installedVersion())
        }
        button.setText(if (found is UpdateResult.Available) R.string.download_update_button else R.string.check_update_button)
        button.setIcon(R.drawable.ic_system_update)
    }

    // Hands the bundled watch app to the Pebble app, which offers to install it on the watch
    private fun installWatchApp() {
        val dir = File(cacheDir, "watchapp").apply { mkdirs() }
        val copy = File(dir, "SleepSense.pbw")
        try {
            assets.open("watch.pbw").use { input -> copy.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            Toast.makeText(this, R.string.install_watch_missing, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(open)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.install_watch_no_handler, Toast.LENGTH_LONG).show()
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
