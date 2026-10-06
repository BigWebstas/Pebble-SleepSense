package net.webstas.sleepsense

import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The noise clips the monitor saved: play, share or delete each one. */
class ClipsActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply {
            addView(list)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(48 + bars.left, 48 + bars.top, 48 + bars.right, 48 + bars.bottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        stopPlayback()
        super.onPause()
    }

    // The theme's main text colour, so the headline stands out in both light and dark mode
    private fun primaryColor(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
        return getColor(value.resourceId)
    }

    private fun stopPlayback() {
        player?.release()
        player = null
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Noise clips"; textSize = 20f; setPadding(0, 0, 0, 16) })
        val clips = NoiseClips.list(this)
        if (clips.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No clips yet. While you track sleep with noise monitoring on, a clip is saved when the room gets suddenly loud."
            })
            return
        }
        clips.forEach { list.addView(row(it)) }
    }

    // clip_<epochMillis>_<peakDb>.wav
    private fun capturedAt(file: File): Long? = file.nameWithoutExtension.split('_').getOrNull(1)?.toLongOrNull()

    private fun peakDb(file: File): String? = file.nameWithoutExtension.split('_').getOrNull(2)

    /** "Tuesday, Oct 6, 2026, 11:24:50 AM" - when the clip's audio begins */
    private fun title(file: File): String =
        capturedAt(file)?.let {
            SimpleDateFormat("EEEE, MMM d, yyyy, h:mm:ss a", Locale.getDefault()).format(Date(it))
        } ?: file.name

    private fun details(file: File): String {
        val seconds = (file.length() - 44) / (NoiseMonitor.SAMPLE_RATE * 2)
        return listOfNotNull(peakDb(file)?.let { "peak $it dB" }, "${seconds}s").joinToString(" · ")
    }

    // What the file is called when shared: readable, not the internal millisecond name
    private fun shareName(file: File): String {
        val stamp = capturedAt(file)?.let { SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(it)) } ?: file.nameWithoutExtension
        return "SleepSense noise $stamp" + (peakDb(file)?.let { " ($it dB)" } ?: "") + ".wav"
    }

    private fun row(file: File): LinearLayout {
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        fun add(label: String, icon: Int, action: () -> Unit) = buttons.addView(Button(this).apply {
            text = label
            setIcon(icon)
            setOnClickListener { action() }
        })
        add("Play", R.drawable.ic_play_arrow) { play(file) }
        add("Share", R.drawable.ic_share) { share(file) }
        add("Delete", R.drawable.ic_delete) { file.delete(); stopPlayback(); render() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            addView(TextView(this@ClipsActivity).apply {
                text = title(file)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryColor())
            })
            addView(TextView(this@ClipsActivity).apply { text = details(file); textSize = 14f })
            addView(buttons)
        }
    }

    private fun play(file: File) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare()
            start()
        }
    }

    private fun share(file: File) {
        // Share a copy under a readable name (the share sheet shows the file's real name)
        val dir = File(cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val copy = file.copyTo(File(dir, shareName(file)), overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share noise clip"))
    }
}
