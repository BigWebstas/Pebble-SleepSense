package net.webstas.sleepsense

import android.content.Intent
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
    private fun describe(file: File): String {
        val parts = file.nameWithoutExtension.split('_')
        val whenText = parts.getOrNull(1)?.toLongOrNull()
            ?.let { SimpleDateFormat("EEE MMM d, HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: file.name
        val peak = parts.getOrNull(2)?.let { " · peak $it dB" } ?: ""
        val seconds = (file.length() - 44) / (NoiseMonitor.SAMPLE_RATE * 2)
        return "$whenText$peak · ${seconds}s"
    }

    private fun row(file: File): LinearLayout {
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        fun add(label: String, action: () -> Unit) = buttons.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
        add("Play") { play(file) }
        add("Share") { share(file) }
        add("Delete") { file.delete(); stopPlayback(); render() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            addView(TextView(this@ClipsActivity).apply { text = describe(file); textSize = 15f })
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
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share noise clip"))
    }
}
