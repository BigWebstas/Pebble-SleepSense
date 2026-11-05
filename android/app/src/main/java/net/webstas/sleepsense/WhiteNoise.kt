package net.webstas.sleepsense

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "WhiteNoisePlayer"
private const val FADE_MS = 12_000L
private const val FADE_STEP_MS = 40L // small gain steps, so the fade is smooth instead of crackling
private const val FADE_IN_MS = 1_500L // when a song ended early and there was no overlap to fade in from

/** The available synthesized sleep sound types. */
enum class WhiteNoiseSound(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
) {
    BROWN("brown", R.string.white_noise_brown_title, R.string.white_noise_brown_desc),
    PINK("pink", R.string.white_noise_pink_title, R.string.white_noise_pink_desc),
    WHITE("white", R.string.white_noise_white_title, R.string.white_noise_white_desc),
}

/** User preferences for white noise playback. */
object WhiteNoisePrefs {
    private const val PREFS = "white_noise"
    private const val KEY_PLAY_WHILE_TRACKING = "play_while_tracking"
    private const val KEY_SOUND = "sound"
    private const val KEY_FILES = "files"
    private const val KEY_USE_FILES = "use_files"

    fun isPlayWhileTracking(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PLAY_WHILE_TRACKING, false)

    fun setPlayWhileTracking(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PLAY_WHILE_TRACKING, enabled)
        }
    }

    fun sound(context: Context): WhiteNoiseSound {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOUND, WhiteNoiseSound.BROWN.id)
        return WhiteNoiseSound.entries.firstOrNull { it.id == id } ?: WhiteNoiseSound.BROWN
    }

    fun setSound(context: Context, sound: WhiteNoiseSound) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SOUND, sound.id)
            putBoolean(KEY_USE_FILES, false)
        }
    }

    /** The audio files chosen as the sleep sound, played in this order and repeated. */
    fun files(context: Context): List<Uri> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILES, null) ?: return emptyList()
        val array = JSONArray(saved)
        return (0 until array.length()).map { Uri.parse(array.getString(it)) }
    }

    /** True when the chosen files, not a synthesized noise, are the sleep sound. */
    fun useFiles(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_FILES, false) && files(context).isNotEmpty()

    fun setFiles(context: Context, uris: List<Uri>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_FILES, JSONArray(uris.map { it.toString() }).toString())
            putBoolean(KEY_USE_FILES, uris.isNotEmpty())
        }
    }
}

/**
 * Real-time synthesis of continuous, seamless soothing sleep noises (Brown, Pink, White)
 * using an AudioTrack stream with soft fade-in/fade-out to avoid clicks.
 */
object WhiteNoisePlayer {
    private const val SAMPLE_RATE = 22050
    private const val CHUNK_SIZE = 1024

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    private var soundType = WhiteNoiseSound.BROWN

    private var worker: Thread? = null

    // Chosen audio files play one after the other, each fading into the next
    private var loop: CrossfadeLoop? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    @Synchronized
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isPlaying)
    }

    @Synchronized
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val playing = isPlaying
        synchronized(this) {
            listeners.toList()
        }.forEach { it(playing) }
    }

    fun setSound(sound: WhiteNoiseSound) {
        soundType = sound
    }

    @Synchronized
    fun start(context: Context, sound: WhiteNoiseSound = WhiteNoisePrefs.sound(context)) {
        soundType = sound
        if (isPlaying) return
        isPlaying = true
        notifyListeners()

        val files = if (WhiteNoisePrefs.useFiles(context)) WhiteNoisePrefs.files(context) else emptyList()
        if (files.isNotEmpty()) {
            startFiles(context.applicationContext, files)
            return
        }

        worker = Thread({ runAudioLoop() }, "white-noise-player").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        notifyListeners()
        worker?.join(1500)
        worker = null
        stopFiles()
    }

    /** Starts again with whatever is now chosen: the noise and the files are different players. */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    private fun startFiles(context: Context, files: List<Uri>) {
        loop = CrossfadeLoop(context, files) {
            isPlaying = false
            notifyListeners()
            stopFiles()
        }.also { it.start() }
    }

    private fun stopFiles() {
        loop?.stop()
        loop = null
    }

    private fun runAudioLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuf, CHUNK_SIZE * 4)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack init failed: $e")
            isPlaying = false
            notifyListeners()
            return
        }

        try {
            track.play()
            val chunk = ShortArray(CHUNK_SIZE)
            val rng = java.util.Random()

            // State for pink noise filter (Kellet approximation)
            var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
            // State for brown noise (leaky integrator)
            var lastBrown = 0f

            // 1-second gentle fade in
            val fadeSamples = (SAMPLE_RATE * 1.0f).toInt()
            var currentSampleCount = 0

            while (isPlaying) {
                val currentType = soundType
                for (i in 0 until CHUNK_SIZE) {
                    val white = rng.nextFloat() * 2f - 1f
                    val sample = when (currentType) {
                        WhiteNoiseSound.BROWN -> {
                            lastBrown = (lastBrown + (0.02f * white)) / 1.02f
                            (lastBrown * 3.5f).coerceIn(-1f, 1f)
                        }
                        WhiteNoiseSound.PINK -> {
                            b0 = 0.99886f * b0 + white * 0.0555179f
                            b1 = 0.99332f * b1 + white * 0.0750759f
                            b2 = 0.96900f * b2 + white * 0.1538520f
                            b3 = 0.86650f * b3 + white * 0.3104856f
                            b4 = 0.55000f * b4 + white * 0.5329522f
                            b5 = -0.7616f * b5 - white * 0.0168980f
                            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
                            b6 = white * 0.115926f
                            (p * 0.11f).coerceIn(-1f, 1f)
                        }
                        WhiteNoiseSound.WHITE -> {
                            (white * 0.35f).coerceIn(-1f, 1f)
                        }
                    }

                    val fadeFactor = if (currentSampleCount < fadeSamples) {
                        currentSampleCount.toFloat() / fadeSamples
                    } else {
                        1f
                    }
                    currentSampleCount++

                    chunk[i] = (sample * 32767f * fadeFactor).toInt().coerceIn(-32768, 32767).toShort()
                }

                var written = 0
                while (written < CHUNK_SIZE && isPlaying) {
                    val n = track.write(chunk, written, CHUNK_SIZE - written)
                    if (n < 0) { isPlaying = false; break }
                    written += n
                }
            }

            // Quick fade out on stop to avoid click (1024 samples ~ 46 ms)
            for (i in 0 until CHUNK_SIZE) {
                val decay = 1f - (i.toFloat() / CHUNK_SIZE)
                chunk[i] = (chunk[i] * decay).toInt().toShort()
            }
            track.write(chunk, 0, CHUNK_SIZE)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack loop error: $e")
        } finally {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                // ignore
            }
            isPlaying = false
            notifyListeners()
        }
    }
}

/**
 * Plays [uris] in order and repeated, starting each one [FADE_MS] before the one before it ends and
 * fading between them, so no song ends abruptly (a single file fades into itself). Two [MediaPlayer]s
 * on the main looper, each holding a wake lock while it plays so the timer keeps running with the
 * screen off; if the timer is late anyway, the next one starts as soon as the current one ends. A file
 * that will not play is skipped; [onFailed] is called when none will.
 */
private class CrossfadeLoop(
    private val context: Context,
    private val uris: List<Uri>,
    private val onFailed: () -> Unit,
) {
    // Its own thread, so a busy screen (its refreshes run on the main thread) cannot hold up the fade's volume steps
    private val thread = HandlerThread("crossfade", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private var current: MediaPlayer? = null
    private var next: MediaPlayer? = null // prepared and waiting
    private var nextIndex = 0
    private var fadeMs = FADE_MS
    private var fading = false
    private var stopped = false
    private val crossfadeTask = Runnable { crossfade() }

    fun start() {
        handler.post {
            open(0) { first, i ->
                first.start()
                begin(first, i)
            }
        }
    }

    fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            current?.release()
            next?.release()
            current = null
            next = null
            thread.quitSafely()
        }
    }

    /** Makes [p], already playing, the current player: preloads the one after it and schedules the cross fade. */
    private fun begin(p: MediaPlayer, index: Int) {
        current = p
        fadeMs = min(FADE_MS, p.duration / 2L)
        p.setOnCompletionListener { if (!stopped && p === current && !fading) advance() }
        open((index + 1) % uris.size) { upcoming, i ->
            next?.release()
            next = upcoming
            nextIndex = i
        }
        handler.postDelayed(crossfadeTask, (p.duration - fadeMs - p.currentPosition).coerceAtLeast(0))
    }

    /** The current one ended before its cross fade started: carry straight on with the next. */
    private fun advance() {
        Log.i(TAG, "song ended before the cross fade, starting the next one")
        handler.removeCallbacks(crossfadeTask)
        current?.release()
        current = null
        val incoming = next
        if (incoming != null) {
            next = null
            startSoftly(incoming)
            begin(incoming, nextIndex)
        } else {
            open(nextIndex) { it, i -> startSoftly(it); begin(it, i) }
        }
    }

    /** Starts [p] from silence over [FADE_IN_MS], so a hard cut from the song before it is not a pop. */
    private fun startSoftly(p: MediaPlayer) {
        p.setVolume(0f, 0f)
        p.start()
        val startedAt = System.nanoTime()
        object : Runnable {
            override fun run() {
                if (stopped) return
                val t = ((System.nanoTime() - startedAt) / 1_000_000f / FADE_IN_MS).coerceAtMost(1f)
                try {
                    p.setVolume(sin(t * PI.toFloat() / 2), sin(t * PI.toFloat() / 2))
                } catch (e: IllegalStateException) {
                    return
                }
                if (t < 1f) handler.postDelayed(this, FADE_STEP_MS)
            }
        }.run()
    }

    private fun crossfade() {
        val outgoing = current ?: return
        val incoming = next
        if (incoming == null) {
            handler.postDelayed(crossfadeTask, 200) // still preparing
            return
        }
        next = null
        Log.i(TAG, "cross fading into the next song")
        fading = true
        incoming.setVolume(0f, 0f)
        val beforeStart = System.nanoTime()
        incoming.start()
        val startedAt = System.nanoTime()
        Log.i(TAG, "cross fade: start() took ${(startedAt - beforeStart) / 1_000_000} ms")
        var lastStep = startedAt
        var worstGap = 0L
        object : Runnable {
            override fun run() {
                val now = System.nanoTime()
                worstGap = max(worstGap, (now - lastStep) / 1_000_000)
                lastStep = now
                val t = ((now - startedAt) / 1_000_000f / fadeMs).coerceAtMost(1f)
                // equal-power curves keep the loudness steady through the overlap
                outgoing.setVolume(cos(t * PI.toFloat() / 2), cos(t * PI.toFloat() / 2))
                incoming.setVolume(sin(t * PI.toFloat() / 2), sin(t * PI.toFloat() / 2))
                if (t < 1f) {
                    handler.postDelayed(this, FADE_STEP_MS)
                } else {
                    Log.i(TAG, "cross fade done, longest wait between volume steps $worstGap ms")
                    fading = false
                    outgoing.release()
                    begin(incoming, nextIndex)
                }
            }
        }.run()
    }

    /** Prepares file [index] (or the first one after it that works) and hands the player over with its index. */
    private fun open(index: Int, tries: Int = 0, onReady: (MediaPlayer, Int) -> Unit) {
        val p = MediaPlayer()
        fun skip() {
            p.release()
            if (stopped) return
            if (tries + 1 < uris.size) open((index + 1) % uris.size, tries + 1, onReady) else onFailed()
        }
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(context, uris[index])
            p.setOnPreparedListener {
                it.setOnErrorListener(null) // once it plays, an error ends it and the completion listener moves on
                if (stopped) it.release() else onReady(it, index)
            }
            p.setOnErrorListener { _, _, _ ->
                Log.w(TAG, "could not play ${uris[index]}")
                skip()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "could not play ${uris[index]}: $e")
            skip()
        }
    }
}
