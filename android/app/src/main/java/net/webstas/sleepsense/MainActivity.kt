package net.webstas.sleepsense

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.transition.TransitionManager
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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
            id<TextView>(R.id.live_readings_text).text = LiveReadings.describe(this@MainActivity)
            refreshChest()
            refreshLastNight()
            handler.postDelayed(this, 3000) // the watch checks in about once a minute and on every change
        }
    }
    private val waveformLoop = object : Runnable {
        override fun run() {
            val box = id<View>(R.id.live_waveform_box)
            if (LiveReadings.hasWaveform()) {
                box.visibility = View.VISIBLE
                id<WaveformView>(R.id.live_waveform).invalidate() // the view redraws itself every frame from here
            } else {
                box.visibility = View.GONE
            }
            handler.postDelayed(this, 500)
        }
    }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNoise() }
    private val requestOximeter =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshOximeter() }
    private val requestOximeterScan =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (oximeterPermissionsGranted(this)) chooseOximeter() else refreshOximeter()
        }
    // Audio files for the sleep sound; read access is kept so they can play on a later night
    private val pickSleepFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            WhiteNoisePrefs.setFiles(this, uris)
            if (WhiteNoisePlayer.isPlaying) WhiteNoisePlayer.restart(this)
            refreshWhiteNoise()
        }

    // The backup folder, and a backup file to restore from
    private val pickBackupFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            Backup.keepAccess(this, uri)
            BackupPrefs.setFolder(this, uri)
            refreshBackup()
        }
    private val pickBackupFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmRestore(uri) }

    private val requestHealth =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refreshHealth() }

    private val requestCalendar =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (CalendarSync.hasPermission(this)) {
                enableCalendarSync()
            } else {
                Toast.makeText(this, R.string.calendar_permission_denied, Toast.LENGTH_SHORT).show()
                id<MaterialSwitch>(R.id.calendar_sync_switch).isChecked = false
            }
        }

    private var update: UpdateResult? = null
    private val whiteNoiseListener = { _: Boolean -> runOnUiThread { refreshWhiteNoise() } }

    private fun <T : android.view.View> id(res: Int): T = findViewById(res)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        handleAlarmIntent(intent)
        startForegroundService(Intent(this, AlarmBridgeService::class.java))
        UiPrefs.applyTheme(this)
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
        id<MaterialSwitch>(R.id.alarm_enabled_switch).apply {
            isChecked = PhoneAlarmSync.isAlarmEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                PhoneAlarmSync.setAlarmEnabled(this@MainActivity, on)
                refreshTracking()
            }
        }
        napTiles.forEach { (button, minutes, _) ->
            id<Button>(button).setOnClickListener { startNap(minutes) }
        }
        id<MaterialSwitch>(R.id.bedtime_switch).setOnCheckedChangeListener { _, on ->
            BedtimeMode.setEnabled(this, on)
            if (on && !BedtimeMode.hasAccess(this)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
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
        id<MaterialSwitch>(R.id.oximeter_switch).apply {
            isChecked = OximeterLog.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                OximeterLog.setEnabled(this@MainActivity, on)
                if (on && !oximeterPermissionsGranted(this@MainActivity)) requestOximeter.launch(oximeterPermissions())
                refreshOximeter()
            }
        }
        id<MaterialSwitch>(R.id.oximeter_hrv_switch).apply {
            isChecked = OximeterLog.hrvEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> OximeterLog.setHrvEnabled(this@MainActivity, on) }
        }
        id<MaterialSwitch>(R.id.chest_switch).apply {
            isChecked = ChestCapture.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                ChestCapture.setEnabled(this@MainActivity, on)
                refreshChest()
            }
        }
        id<Button>(R.id.oximeter_choose_button).setOnClickListener {
            if (oximeterPermissionsGranted(this)) chooseOximeter() else requestOximeterScan.launch(oximeterPermissions())
        }
        id<Button>(R.id.clips_button).setOnClickListener { startActivity(Intent(this, ClipsActivity::class.java)) }
        id<Button>(R.id.history_button).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        val advancedBox = id<LinearLayout>(R.id.advanced_box)
        val advancedToggle = id<Button>(R.id.advanced_toggle_button).apply {
            setIcon(R.drawable.ic_expand_more)
            setOnClickListener {
                val show = advancedBox.visibility != View.VISIBLE
                TransitionManager.beginDelayedTransition(id(R.id.content))
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

        setUpWebhookSettings()
        id<Button>(R.id.home_assistant_button).setOnClickListener { startActivity(Intent(this, HomeAssistantActivity::class.java)) }
        id<MaterialSwitch>(R.id.calendar_sync_switch).apply {
            isChecked = CalendarSync.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (!on) CalendarSync.setEnabled(this@MainActivity, false)
                else if (CalendarSync.hasPermission(this@MainActivity)) enableCalendarSync()
                else requestCalendar.launch(CalendarSync.PERMISSIONS)
            }
        }
        id<Button>(R.id.calendar_button).setOnClickListener {
            if (CalendarSync.hasPermission(this)) chooseCalendar() else requestCalendar.launch(CalendarSync.PERMISSIONS)
        }
        id<Button>(R.id.grant_hc_button).setOnClickListener { requestHealth.launch(setOf(WRITE_SLEEP, WRITE_HEART_RATE, WRITE_OXYGEN_SATURATION, WRITE_RESPIRATORY_RATE, WRITE_HRV)) }
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

        id<MaterialSwitch>(R.id.true_black_switch).apply {
            isChecked = UiPrefs.trueBlack(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                UiPrefs.setTrueBlack(this@MainActivity, on)
                recreate()
            }
        }
        setUpBackup()
        id<MaterialSwitch>(R.id.xdrip_switch).apply {
            isChecked = GlucoseLog.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                GlucoseLog.setEnabled(this@MainActivity, on)
                refreshXdrip()
            }
        }
        id<View>(R.id.last_night_body).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        setupCards()
        attachHaptics(id(R.id.root))
    }

    /** Section cards fold up on a tap of their header (remembered), and slide in when the screen opens. */
    private fun setupCards() {
        val content = id<LinearLayout>(R.id.content)
        val cards = (content.children + id<LinearLayout>(R.id.advanced_box).children).filterIsInstance<MaterialCardView>()
        for (card in cards.filter { it.id != R.id.tracking_card }) {
            val inner = card.getChildAt(0) as android.view.ViewGroup
            val header = inner.getChildAt(0) as TextView
            val body = inner.getChildAt(1)
            val key = resources.getResourceEntryName(header.id)
            val icon = header.compoundDrawablesRelative[0]
            fun render(collapsed: Boolean) {
                body.visibility = if (collapsed) View.GONE else View.VISIBLE
                val chevron = AppCompatResources.getDrawable(this, if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
                header.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, chevron, null)
            }
            // The advanced cards start folded, all but Live Readings
            val foldedByDefault = card.parent === id<LinearLayout>(R.id.advanced_box) && header.id != R.id.live_section_label
            render(UiPrefs.isCollapsed(this, key, foldedByDefault))
            header.setOnClickListener {
                val collapse = body.visibility == View.VISIBLE
                TransitionManager.beginDelayedTransition(content)
                render(collapse)
                UiPrefs.setCollapsed(this, key, collapse)
            }
        }
        val rise = 24 * resources.displayMetrics.density
        content.children.filterIsInstance<MaterialCardView>().forEachIndexed { i, card ->
            card.alpha = 0f
            card.translationY = rise
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(350)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Off again if the user came back from the settings page without granting access
        id<MaterialSwitch>(R.id.bedtime_switch).isChecked = BedtimeMode.isEnabled(this) && BedtimeMode.hasAccess(this)
        refreshNoise()
        refreshOximeter()
        refreshHealth()
        refreshWhiteNoise()
        refreshBackup()
        refreshXdrip()
        refreshCalendar()
        refreshLastNight(force = true)
        handler.post(refreshLoop)
        handler.post(waveformLoop)
    }

    override fun onDestroy() {
        WhiteNoisePlayer.removeListener(whiteNoiseListener)
        handler.removeCallbacks(refreshLoop)
        handler.removeCallbacks(waveformLoop)
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        handler.removeCallbacks(waveformLoop)
        stopDotPulses()
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
        setTrackingDots(tracking)
        id<TextView>(R.id.alarm_status_text).text = PhoneAlarmSync.describe(this).lineSequence().first()
        id<Button>(R.id.tracking_button).setText(if (tracking) R.string.stop_tracking_button else R.string.start_tracking_button)
        setPlayStopIcon(R.id.tracking_button, tracking)
        val since = WidgetState.since(this)
        val elapsed = if (tracking && since > 0) duration((System.currentTimeMillis() - since) / 60_000) else null
        id<TextView>(R.id.tracking_elapsed_text).apply {
            visibility = if (elapsed != null) View.VISIBLE else View.GONE
            text = elapsed
        }
        refreshStrip(tracking, elapsed)
        refreshAlarmSettings()
        id<TextView>(R.id.home_assistant_status_text).setText(HomeAssistant.statusText(this))
    }

    private fun duration(minutes: Long) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

    // The bar pinned above everything while tracking: time so far and the newest readings
    private fun refreshStrip(tracking: Boolean, elapsed: String?) {
        val strip = id<View>(R.id.live_strip)
        val show = tracking && elapsed != null
        if (show != (strip.visibility == View.VISIBLE)) {
            TransitionManager.beginDelayedTransition(id(R.id.root))
            strip.visibility = if (show) View.VISIBLE else View.GONE
        }
        if (!show) return
        val now = System.currentTimeMillis()
        fun live(stream: LiveReadings.Stream) = LiveReadings.get(stream)?.takeIf { now - it.at < 10 * 60_000 }?.value?.toString()
        id<TextView>(R.id.strip_elapsed_value).text = elapsed
        id<TextView>(R.id.strip_heart_value).text = live(LiveReadings.Stream.HEART_RATE) ?: live(LiveReadings.Stream.OXIMETER_PULSE) ?: "--"
        id<TextView>(R.id.strip_spo2_value).text = live(LiveReadings.Stream.SPO2) ?: "--"
        id<TextView>(R.id.strip_breathing_value).text = live(LiveReadings.Stream.BREATHING) ?: "--"
    }

    private var lastNightStamp = -1L

    // The newest finished night: stars as a ring, time asleep, and the stages as a ribbon
    private fun refreshLastNight(force: Boolean = false) {
        val stamp = SessionStore.modified(this)
        if (!force && stamp == lastNightStamp) return
        lastNightStamp = stamp
        val night = SessionStore.lastFinishedNight(this)
        id<View>(R.id.last_night_card).visibility = if (night == null) View.GONE else View.VISIBLE
        if (night == null) return
        val rating = night.optInt("rating").coerceIn(0, 5)
        id<CircularProgressIndicator>(R.id.rating_ring).setProgressCompat(rating * 20, true)
        id<TextView>(R.id.rating_ring_text).text = if (rating > 0) "$rating★" else "–"
        id<TextView>(R.id.last_night_summary).text =
            getString(R.string.last_night_summary, duration(SessionStore.asleepMinutes(night)))
        id<TextView>(R.id.last_night_times).text = "${clock(night.optLong("start"))} – ${clock(night.optLong("end"))}"
        id<StageRibbonView>(R.id.stage_ribbon).set(night)
        id<TextView>(R.id.stage_legend).text = SpannableStringBuilder().apply {
            listOf(R.string.live_stage_awake to R.color.stage_awake, R.string.live_stage_light to R.color.stage_light,
                R.string.live_stage_deep to R.color.stage_deep, R.string.live_stage_rem to R.color.stage_rem,
            ).forEachIndexed { i, (label, color) ->
                if (i > 0) append("   ")
                val from = length
                append("●")
                setSpan(ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, color)), from, length, 0)
                append(" ").append(getString(label))
            }
        }
    }

    private val playStopState = mutableMapOf<Int, Boolean>()

    // The play and stop icons morph into each other when a button flips; the first time it is just set
    private fun setPlayStopIcon(buttonId: Int, playing: Boolean) {
        val button = id<MaterialButton>(buttonId)
        val previous = playStopState.put(buttonId, playing)
        if (previous == playing) return
        if (previous == null) {
            button.setIcon(if (playing) R.drawable.ic_stop else R.drawable.ic_play_arrow)
            return
        }
        button.morphPlayStop(playing)
    }

    private val dotPulses = mutableListOf<ObjectAnimator>()

    // Green dots either side of the status pulse while tracking, out of step with each other
    private fun setTrackingDots(tracking: Boolean) {
        val dots = listOf(id<View>(R.id.tracking_dot_start), id<View>(R.id.tracking_dot_end))
        dots.forEach { it.visibility = if (tracking) View.VISIBLE else View.GONE }
        if (!tracking) {
            stopDotPulses()
        } else if (dotPulses.isEmpty()) {
            dots.forEachIndexed { i, dot ->
                dotPulses += ObjectAnimator.ofPropertyValuesHolder(
                    dot,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.25f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1f),
                ).apply {
                    duration = 900
                    startDelay = i * 450L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
            }
        }
    }

    private fun stopDotPulses() {
        dotPulses.forEach { it.cancel() }
        dotPulses.clear()
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
        id<MaterialSwitch>(R.id.alarm_enabled_switch).isChecked = PhoneAlarmSync.isAlarmEnabled(this)
        id<TextView>(R.id.smart_window_text).text = getString(
            R.string.smart_window,
            PhoneAlarmSync.smartWindowMinutes(this)?.let { getString(R.string.smart_window_minutes, it) }
                ?: getString(R.string.smart_window_unset),
        )
        refreshNapTiles()
    }

    private val napTiles = listOf(
        Triple(R.id.nap_30_button, 30, R.string.nap_button_30),
        Triple(R.id.nap_45_button, 45, R.string.nap_button_45),
        Triple(R.id.nap_60_button, 60, R.string.nap_button_60),
    )
    private val napNormal = mutableMapOf<Int, Pair<ColorStateList?, ColorStateList>>()

    // The tile of the nap that is waiting turns solid and shows the time it will wake you
    private fun refreshNapTiles() {
        val nap = PhoneAlarmSync.activeNap(this)
        for ((buttonId, minutes, label) in napTiles) {
            val button = id<MaterialButton>(buttonId)
            val (tint, text) = napNormal.getOrPut(buttonId) { button.backgroundTintList to button.textColors }
            if (nap?.minutes == minutes) {
                button.text = getString(R.string.nap_active, minutes, "%02d:%02d".format(nap.hour, nap.minute))
                button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary))
                button.setTextColor(MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnPrimary))
            } else {
                button.setText(label)
                button.backgroundTintList = tint
                button.setTextColor(text)
            }
        }
    }

    private fun startNap(minutes: Int) {
        val (hour, minute) = PhoneAlarmSync.startNap(this, minutes)
        id<MaterialSwitch>(R.id.alarm_sync_switch).isChecked = false
        SleepWidgetProvider.refreshAll(this)
        if (!WidgetState.tracking(this)) TrackingControl.start(this)
        refreshAlarmSettings()
        refreshTracking()
        Toast.makeText(this, getString(R.string.nap_started, "%02d:%02d".format(hour, minute)), Toast.LENGTH_LONG).show()
    }

    private fun chooseAlarmTime() {
        val (hour, minute) = PhoneAlarmSync.customAlarm(this) ?: (7 to 0)
        TimePickerDialog(this, { _, h, m ->
            PhoneAlarmSync.setCustomAlarm(this, h, m)
            refreshAlarmSettings()
        }, hour, minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun refreshCalendar() {
        id<Button>(R.id.calendar_button).text = getString(
            R.string.calendar_choose_button,
            CalendarSync.calendarName(this) ?: getString(R.string.calendar_none),
        )
    }

    private fun enableCalendarSync() {
        CalendarSync.setEnabled(this, true)
        if (CalendarSync.calendarName(this) == null) chooseCalendar() else syncCalendar()
    }

    private fun syncCalendar() {
        lifecycleScope.launch(Dispatchers.IO) { CalendarSync.sync(applicationContext) }
    }

    private fun chooseCalendar() {
        val calendars = CalendarSync.calendars(this)
        if (calendars.isEmpty()) {
            Toast.makeText(this, R.string.calendar_no_calendars, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calendar_choose_title)
            .setItems(calendars.map { it.name }.toTypedArray()) { _, which ->
                CalendarSync.setCalendar(this, calendars[which].id)
                refreshCalendar()
                if (CalendarSync.isEnabled(this)) syncCalendar()
            }
            .show()
    }

    private fun chooseSnooze() {
        val lengths = intArrayOf(0, 5, 10, 15, 20)
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

    private fun setUpWebhookSettings() {
        val details = id<View>(R.id.webhook_details)
        id<TextView>(R.id.webhook_url_text).text =
            getString(R.string.webhook_url, "http://127.0.0.1:$WEBHOOK_PORT/toggle")
        id<MaterialSwitch>(R.id.webhook_switch).apply {
            isChecked = WebhookPrefs.isEnabled(this@MainActivity)
            details.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on ->
                WebhookPrefs.setEnabled(this@MainActivity, on)
                details.visibility = if (on) View.VISIBLE else View.GONE
                // The webhook is served by the bridge service, which has to be running
                if (on) startForegroundService(Intent(this@MainActivity, AlarmBridgeService::class.java))
            }
        }
        id<Button>(R.id.webhook_copy_token_button).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("webhook token", WebhookPrefs.token(this)))
            Toast.makeText(this, R.string.webhook_token_copied, Toast.LENGTH_SHORT).show()
        }
        id<Button>(R.id.webhook_regenerate_button).setOnClickListener {
            WebhookPrefs.regenerateToken(this)
            Toast.makeText(this, R.string.webhook_token_regenerated, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshChest() {
        val rates = ChestCapture.rates(this)
        id<View>(R.id.chest_rate_box).visibility = if (rates.size >= 2) View.VISIBLE else View.GONE
        id<ChestRateView>(R.id.chest_rate_view).setRates(rates)
        id<TextView>(R.id.chest_status_text).setText(
            if (ChestCapture.isEnabled(this)) R.string.chest_on else R.string.chest_off,
        )
    }

    private fun refreshOximeter() {
        val summary = OximeterLog.lastRun(this)
        id<TextView>(R.id.oximeter_status_text).text = when {
            !OximeterLog.isEnabled(this) -> getString(R.string.oximeter_off)
            !oximeterPermissionsGranted(this) -> getString(R.string.oximeter_needs_permission)
            OximeterLog.address(this) == null -> getString(R.string.oximeter_no_device)
            summary != null ->
                getString(R.string.oximeter_last_run, summary.average, summary.lowest, summary.minutesLow)
            else -> getString(R.string.oximeter_ready, OximeterLog.name(this) ?: OximeterLog.address(this))
        }
    }

    private fun chooseOximeter() {
        Toast.makeText(this, R.string.oximeter_scanning, Toast.LENGTH_SHORT).show()
        OximeterScanner.scan(this) { found ->
            if (isFinishing || isDestroyed) return@scan
            if (found.isEmpty()) {
                Toast.makeText(this, R.string.oximeter_none_found, Toast.LENGTH_LONG).show()
                return@scan
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.oximeter_choose_title)
                .setItems(found.map { it.name }.toTypedArray()) { _, which ->
                    OximeterLog.setDevice(this, found[which].address, found[which].name)
                    refreshOximeter()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

    private fun setUpBackup() {
        id<Button>(R.id.backup_folder_button).setOnClickListener { pickBackupFolder.launch(BackupPrefs.folder(this)) }
        id<MaterialSwitch>(R.id.backup_on_start_switch).apply {
            isChecked = BackupPrefs.onStart(this@MainActivity)
            setOnCheckedChangeListener { _, on -> BackupPrefs.setOnStart(this@MainActivity, on) }
        }
        id<MaterialSwitch>(R.id.backup_on_stop_switch).apply {
            isChecked = BackupPrefs.onStop(this@MainActivity)
            setOnCheckedChangeListener { _, on -> BackupPrefs.setOnStop(this@MainActivity, on) }
        }
        id<Button>(R.id.backup_now_button).setOnClickListener {
            if (BackupPrefs.folder(this) == null) {
                pickBackupFolder.launch(null)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Backup.write(this@MainActivity) } }
                Toast.makeText(
                    this@MainActivity,
                    result.getOrNull()?.let { getString(R.string.backup_done, it) } ?: getString(R.string.backup_failed),
                    Toast.LENGTH_LONG,
                ).show()
                refreshBackup()
            }
        }
        id<Button>(R.id.backup_restore_button).setOnClickListener { pickBackupFile.launch(arrayOf("application/zip", "application/octet-stream")) }
    }

    private fun refreshXdrip() {
        val status = id<TextView>(R.id.xdrip_status_text)
        status.visibility = if (GlucoseLog.isEnabled(this)) View.VISIBLE else View.GONE
        val last = GlucoseLog.latest(this)
        status.text = if (last == null) getString(R.string.xdrip_setup) else
            getString(R.string.xdrip_last, last.second, android.text.format.DateUtils.getRelativeTimeSpanString(last.first))
    }

    private fun refreshBackup() {
        val folder = BackupPrefs.folder(this)
        val last = BackupPrefs.last(this)
        id<TextView>(R.id.backup_status_text).text = when {
            folder == null -> getString(R.string.backup_status_none)
            last == null -> getString(R.string.backup_status_folder, Uri.decode(folder.lastPathSegment ?: folder.toString()))
            else -> getString(R.string.backup_status_folder, Uri.decode(folder.lastPathSegment ?: folder.toString())) + "\n" +
                getString(R.string.backup_status_last, android.text.format.DateUtils.getRelativeTimeSpanString(last.first), last.second)
        }
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore_title)
            .setMessage(R.string.backup_restore_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_restore_confirm) { _, _ ->
                lifecycleScope.launch {
                    val count = withContext(Dispatchers.IO) {
                        runCatching { contentResolver.openInputStream(uri)!!.use { Backup.restore(this@MainActivity, it) } }
                    }
                    if (count.isSuccess) {
                        Toast.makeText(this@MainActivity, getString(R.string.backup_restored, count.getOrThrow()), Toast.LENGTH_LONG).show()
                        recreate() // every screen reads the restored settings again
                    } else {
                        Toast.makeText(this@MainActivity, R.string.backup_restore_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun refreshWhiteNoise() {
        val sound = WhiteNoisePrefs.sound(this)
        id<Button>(R.id.white_noise_sound_button).text = getString(
            R.string.white_noise_sound_label,
            if (WhiteNoisePrefs.useSpotify(this)) {
                getString(R.string.white_noise_spotify_title)
            } else if (WhiteNoisePrefs.useFiles(this)) {
                resources.getQuantityString(R.plurals.white_noise_files_title, WhiteNoisePrefs.files(this).size, WhiteNoisePrefs.files(this).size)
            } else {
                getString(sound.titleRes)
            },
        )

        val playing = WhiteNoisePlayer.isPlaying
        id<Button>(R.id.white_noise_play_button).setText(if (playing) R.string.white_noise_stop_button else R.string.white_noise_play_button)
        setPlayStopIcon(R.id.white_noise_play_button, playing)
    }

    // The link of a Spotify playlist, offered from the clipboard when it holds one
    private fun askSpotifyLink() {
        val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.white_noise_spotify_hint)
            setText(clip?.takeIf { SpotifyLink.parse(it) != null } ?: WhiteNoisePrefs.spotify(this@MainActivity) ?: "")
            setSingleLine()
        }
        val spotify = packageManager.getLaunchIntentForPackage("com.spotify.music")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.white_noise_spotify_title)
            .setMessage(R.string.white_noise_spotify_message)
            .setView(input)
            .apply {
                // Spotify itself: find the playlist there, then Share it to SleepSense
                if (spotify != null) setNeutralButton(R.string.white_noise_spotify_open) { _, _ -> startActivity(spotify) }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val address = SpotifyLink.parse(input.text.toString())
                if (address == null) {
                    Toast.makeText(this, R.string.white_noise_spotify_invalid, Toast.LENGTH_LONG).show()
                } else {
                    WhiteNoisePrefs.setSpotify(this, address)
                    if (WhiteNoisePlayer.isPlaying) WhiteNoisePlayer.restart(this)
                    refreshWhiteNoise()
                    // The one-time permission for this app to control Spotify, asked now while it is in front
                    SpotifyRemote.connect(this, true) { ok ->
                        runOnUiThread {
                            Toast.makeText(this, if (ok) R.string.white_noise_spotify_connected else R.string.white_noise_spotify_not_connected, Toast.LENGTH_LONG).show()
                        }
                        if (ok) SpotifyRemote.disconnect()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseWhiteNoiseSound() {
        val current = WhiteNoisePrefs.sound(this)
        val sounds = WhiteNoiseSound.entries
        // The noises, then one more entry for the phone's own audio files
        val labels = (sounds.map { getString(it.descRes) } + getString(R.string.white_noise_files_desc) + getString(R.string.white_noise_spotify_desc)).toTypedArray()
        val checkedIndex = when {
            WhiteNoisePrefs.useSpotify(this) -> sounds.size + 1
            WhiteNoisePrefs.useFiles(this) -> sounds.size
            else -> sounds.indexOf(current).coerceAtLeast(0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.white_noise_sound_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                dialog.dismiss()
                if (which == sounds.size) {
                    pickSleepFiles.launch(arrayOf("audio/*"))
                    return@setSingleChoiceItems
                }
                if (which == sounds.size + 1) {
                    askSpotifyLink()
                    return@setSingleChoiceItems
                }
                val chosen = sounds[which]
                WhiteNoisePrefs.setSound(this, chosen)
                if (WhiteNoisePlayer.isPlaying) WhiteNoisePlayer.restart(this)
                refreshWhiteNoise()
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
