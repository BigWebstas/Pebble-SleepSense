package net.webstas.sleepsense

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** The noise clips the monitor saved, each with its sound level over time: play, share or delete each one. */
class ClipsActivity : AppCompatActivity() {
    private class Row(val file: File, val levels: ClipLevelView, val play: MaterialButton)

    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private var player: MediaPlayer? = null
    private var playing: Row? = null

    // Moves the playhead of the clip that is playing
    private val playhead = object : Runnable {
        override fun run() {
            val p = player ?: return
            playing?.levels?.progress = p.currentPosition / p.duration.coerceAtLeast(1).toFloat()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        list = findViewById(R.id.clip_list)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
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
        handler.removeCallbacks(playhead)
        player?.release()
        player = null
        playing?.let {
            it.levels.progress = -1f
            it.play.setText("Play")
            it.play.morphPlayStop(false)
        }
        playing = null
    }

    private fun render() {
        stopPlayback()
        for (i in list.childCount - 1 downTo 0) if (list.getChildAt(i).id == View.NO_ID) list.removeViewAt(i)
        rows.clear()
        val clips = NoiseClips.list(this)
        findViewById<View>(R.id.clips_empty).visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        val rise = 24 * resources.displayMetrics.density
        clips.forEachIndexed { i, file ->
            val card = row(file)
            list.addView(card)
            card.alpha = 0f
            card.translationY = rise
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(350)
                .setInterpolator(DecelerateInterpolator()).start()
        }
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

    private fun row(file: File): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_clip, list, false)
        card.findViewById<TextView>(R.id.clip_title).text = title(file)
        card.findViewById<TextView>(R.id.clip_details).text = details(file)
        val levels = card.findViewById<ClipLevelView>(R.id.clip_levels)
        val play = card.findViewById<MaterialButton>(R.id.clip_play)
        val row = Row(file, levels, play).also { rows += it }
        // Reading the whole clip is quick, but not something to do while the card slides in
        thread {
            val values = runCatching { ClipLevelView.levels(file) }.getOrNull()
            runOnUiThread { if (!isDestroyed) levels.levels = values }
        }
        play.setOnClickListener { if (playing === row) stopPlayback() else play(row) }
        card.findViewById<View>(R.id.clip_share).setOnClickListener { share(file) }
        card.findViewById<View>(R.id.clip_delete).setOnClickListener { file.delete(); render() }
        attachHaptics(card)
        return card
    }

    private fun play(row: Row) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(row.file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare()
            start()
        }
        playing = row
        row.play.setText("Stop")
        row.play.morphPlayStop(true)
        handler.post(playhead)
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
