package net.webstas.sleepsense

import android.Manifest
import android.app.TimePickerDialog
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
import android.text.format.DateFormat
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class MainActivity : AppCompatActivity() {
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
    private val whiteNoiseListener = { _: Boolean -> runOnUiThread { refreshWhiteNoise() } }

    private fun <T : android.view.View> id(res: Int): T = findViewById(res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivityIfAvailable(this)
        DynamicColors.applyToActivitiesIfAvailable(application)
        handleAlarmIntent(intent)
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
        id<Button>(R.id.home_assistant_button).setIcon(R.drawable.ic_home)
        id<Button>(R.id.grant_hc_button).setIcon(R.drawable.ic_favorite)
        id<Button>(R.id.widget_button).setIcon(R.drawable.ic_widgets)
        id<Button>(R.id.tracking_button).setOnClickListener {
            if (WidgetState.tracking(this)) TrackingControl.stop(this) else TrackingControl.start(this)
            refreshTracking()
        }
        id<Button>(R.id.clock_button).setOnClickListener { openClock() }

        id<MaterialSwitch>(R.id.alarm_sync_switch).apply {
            isChecked = PhoneAlarmSync.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                PhoneAlarmSync.setEnabled(this@MainActivity, on)
                refreshTracking()
            }
        }
        id<Button>(R.id.alarm_time_button).setOnClickListener { chooseAlarmTime() }
        id<Button>(R.id.snooze_button).setOnClickListener { chooseSnooze() }
        id<MaterialSwitch>(R.id.noise_switch).apply {
            isChecked = NoiseClips.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                NoiseClips.setEnabled(this@MainActivity, on)
                if (on && !micGranted()) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                refreshNoise()
            }
        }
        id<Button>(R.id.noise_sensitivity_button).apply {
            setIcon(R.drawable.ic_tune)
            setOnClickListener { chooseNoiseSensitivity() }
        }
        id<Button>(R.id.clips_button).setOnClickListener { startActivity(Intent(this, ClipsActivity::class.java)) }
        id<Button>(R.id.history_button).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        val advancedBox = id<LinearLayout>(R.id.advanced_box)
        val advancedToggle = id<Button>(R.id.advanced_toggle_button).apply {
            setIcon(R.drawable.ic_expand_more)
            setOnClickListener {
                val show = advancedBox.visibility != View.VISIBLE
                advancedBox.visibility = if (show) View.VISIBLE else View.GONE
                setText(if (show) R.string.hide_advanced_button else R.string.show_advanced_button)
                setIcon(if (show) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            }
        }

        id<MaterialSwitch>(R.id.white_noise_tracking_switch).apply {
            isChecked = WhiteNoisePrefs.isPlayWhileTracking(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                WhiteNoisePrefs.setPlayWhileTracking(this@MainActivity, on)
                if (on && WidgetState.tracking(this@MainActivity) && !WhiteNoisePlayer.isPlaying) {
                    WhiteNoisePlayer.start(this@MainActivity)
                }
            }
        }
        id<Button>(R.id.white_noise_sound_button).apply {
            setIcon(R.drawable.ic_graphic_eq)
            setOnClickListener { chooseWhiteNoiseSound() }
        }
        id<Button>(R.id.white_noise_play_button).setOnClickListener {
            if (WhiteNoisePlayer.isPlaying) {
                WhiteNoisePlayer.stop()
            } else {
                WhiteNoisePlayer.start(this)
            }
        }
        WhiteNoisePlayer.addListener(whiteNoiseListener)

        id<Button>(R.id.home_assistant_button).setOnClickListener { startActivity(Intent(this, HomeAssistantActivity::class.java)) }
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
        refreshWhiteNoise()
        handler.post(refreshLoop)
    }

    override fun onDestroy() {
        WhiteNoisePlayer.removeListener(whiteNoiseListener)
        handler.removeCallbacks(refreshLoop)
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
    }

    private fun handleAlarmIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                if (intent.hasExtra(AlarmClock.EXTRA_HOUR)) {
                    val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, 7).coerceIn(0, 23)
                    val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
                    PhoneAlarmSync.setEnabled(this, false)
                    PhoneAlarmSync.setCustomAlarm(this, hour, minute)
                    Toast.makeText(
                        this,
                        getString(R.string.alarm_set_toast, "%02d:%02d".format(hour, minute)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshTracking()
                } else if (!PhoneAlarmSync.isEnabled(this)) {
                    chooseAlarmTime()
                }
            }
            AlarmClock.ACTION_SHOW_ALARMS -> {
                // Opened to view/manage alarms; main screen already presents the alarm status and controls.
            }
        }
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
        refreshAlarmSettings()
        id<TextView>(R.id.home_assistant_status_text).setText(HomeAssistant.statusText(this))
    }

    // The alarm time can be set here only while the phone's own alarm isn't being followed
    private fun refreshAlarmSettings() {
        id<Button>(R.id.alarm_time_button).apply {
            val synced = PhoneAlarmSync.isEnabled(this@MainActivity)
            isEnabled = !synced
            text = if (synced) getString(R.string.alarm_time_synced) else getString(
                R.string.alarm_time_button,
                PhoneAlarmSync.customAlarm(this@MainActivity)?.let { "%02d:%02d".format(it.first, it.second) }
                    ?: getString(R.string.alarm_time_none),
            )
        }
        val snooze = PhoneAlarmSync.snoozeMinutes(this)
        id<Button>(R.id.snooze_button).text = getString(
            R.string.snooze_button,
            when (snooze) {
                null -> getString(R.string.snooze_unset)
                0 -> getString(R.string.snooze_off)
                else -> getString(R.string.snooze_minutes, snooze)
            },
        )
    }

    private fun chooseAlarmTime() {
        val (hour, minute) = PhoneAlarmSync.customAlarm(this) ?: (7 to 0)
        TimePickerDialog(this, { _, h, m ->
            PhoneAlarmSync.setCustomAlarm(this, h, m)
            refreshAlarmSettings()
        }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun chooseSnooze() {
        val lengths = intArrayOf(0, 5, 9, 10, 15, 20)
        val labels = lengths.map { if (it == 0) getString(R.string.snooze_off) else getString(R.string.snooze_minutes, it) }
        MaterialAlertDialogBuilder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                PhoneAlarmSync.setSnoozeMinutes(this, lengths[which])
                refreshAlarmSettings()
            }
            .show()
    }

    // Opens the Clock app's alarm list: the standard Clock app if there are several that can
    private fun openClock() {
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val handlers = packageManager.queryIntentActivities(show, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
        val target = handlers.firstOrNull { it == "com.google.android.deskclock" || it == "com.android.deskclock" }
            ?: handlers.firstOrNull()
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

        if (found is UpdateResult.Available) {
            val advancedBox = id<LinearLayout>(R.id.advanced_box)
            if (advancedBox.visibility != View.VISIBLE) {
                advancedBox.visibility = View.VISIBLE
                id<Button>(R.id.advanced_toggle_button).apply {
                    setText(R.string.hide_advanced_button)
                    setIcon(R.drawable.ic_expand_less)
                }
            }
        }
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
        val enabled = NoiseClips.isEnabled(this)
        id<TextView>(R.id.noise_status_text).text = when {
            !enabled -> getString(R.string.noise_off)
            !micGranted() -> getString(R.string.noise_needs_permission)
            else -> getString(R.string.noise_listening, clips)
        }
        val sens = NoiseClips.sensitivity(this)
        id<Button>(R.id.noise_sensitivity_button).apply {
            visibility = if (enabled) View.VISIBLE else View.GONE
            text = getString(R.string.noise_sensitivity_button, getString(sens.titleRes))
        }
    }

    private fun chooseNoiseSensitivity() {
        val current = NoiseClips.sensitivity(this)
        val levels = NoiseSensitivity.entries
        val labels = levels.map { getString(it.descRes) }.toTypedArray()
        val checkedIndex = levels.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.noise_sensitivity_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                NoiseClips.setSensitivity(this, levels[which])
                refreshNoise()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshWhiteNoise() {
        val sound = WhiteNoisePrefs.sound(this)
        id<Button>(R.id.white_noise_sound_button).text =
            getString(R.string.white_noise_sound_label, getString(sound.titleRes))

        val playing = WhiteNoisePlayer.isPlaying
        id<Button>(R.id.white_noise_play_button).apply {
            setText(if (playing) R.string.white_noise_stop_button else R.string.white_noise_play_button)
            setIcon(if (playing) R.drawable.ic_stop else R.drawable.ic_play_arrow)
        }
    }

    private fun chooseWhiteNoiseSound() {
        val current = WhiteNoisePrefs.sound(this)
        val sounds = WhiteNoiseSound.entries
        val labels = sounds.map { getString(it.descRes) }.toTypedArray()
        val checkedIndex = sounds.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.white_noise_sound_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val chosen = sounds[which]
                WhiteNoisePrefs.setSound(this, chosen)
                if (WhiteNoisePlayer.isPlaying) {
                    WhiteNoisePlayer.setSound(chosen)
                }
                refreshWhiteNoise()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
