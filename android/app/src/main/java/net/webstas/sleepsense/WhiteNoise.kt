package net.webstas.sleepsense

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import androidx.core.content.edit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "WhiteNoisePlayer"
private const val FADE_MS = 12_000L

/** The available synthesized sleep sound types. */
enum class WhiteNoiseSound(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
) {
    BROWN("brown", R.string.white_noise_brown_title, R.string.white_noise_brown_desc),
    PINK("pink", R.string.white_noise_pink_title, R.string.white_noise_pink_desc),
    WHITE("white", R.string.white_noise_white_title, R.string.white_noise_white_desc),
    SONG("song", R.string.white_noise_song_title, R.string.white_noise_song_desc),
}

/** User preferences for white noise playback. */
object WhiteNoisePrefs {
    private const val PREFS = "white_noise"
    private const val KEY_PLAY_WHILE_TRACKING = "play_while_tracking"
    private const val KEY_SOUND = "sound"
    private const val KEY_SONG = "song_uri"

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
        }
    }

    /** The audio file picked for [WhiteNoiseSound.SONG], if any. */
    fun song(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SONG, null)?.let(Uri::parse)

    fun setSong(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SONG, uri.toString())
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
        val song = WhiteNoisePrefs.song(context)?.takeIf { sound == WhiteNoiseSound.SONG }
        soundType = if (sound == WhiteNoiseSound.SONG && song == null) WhiteNoiseSound.BROWN else sound
        if (isPlaying) return
        isPlaying = true
        notifyListeners()

        if (song != null) {
            loop = CrossfadeLoop(context.applicationContext, song) {
                stop()
                Log.w(TAG, "could not play $song")
            }.also { it.start() }
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
        loop?.stop()
        loop = null
        worker?.join(1500)
        worker = null
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
                        WhiteNoiseSound.WHITE, WhiteNoiseSound.SONG -> { // a song never reaches this loop
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
 * Loops one audio file by starting a second copy [FADE_MS] before the first one ends and fading
 * between them, so the loop point is never an abrupt end. Two [MediaPlayer]s on the main looper, each
 * holding a wake lock while it plays so the timer keeps running with the screen off; if the timer
 * is late anyway, the next copy starts as soon as the current one ends. [onFailed] is called if the
 * file cannot be opened.
 */
private class CrossfadeLoop(
    private val context: Context,
    private val uri: Uri,
    private val onFailed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaPlayer? = null
    private var next: MediaPlayer? = null // the same file, prepared and waiting
    private var fadeMs = FADE_MS
    private var fading = false
    private var stopped = false
    private val crossfadeTask = Runnable { crossfade() }

    fun start() {
        handler.post {
            open { first ->
                first.start()
                begin(first)
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
        }
    }

    /** Makes [p], already playing, the current player: preloads the next one and schedules the cross fade. */
    private fun begin(p: MediaPlayer) {
        current = p
        fadeMs = min(FADE_MS, p.duration / 2L)
        p.setOnCompletionListener { if (!stopped && p === current && !fading) advance() }
        open { next?.release(); next = it }
        handler.postDelayed(crossfadeTask, (p.duration - fadeMs - p.currentPosition).coerceAtLeast(0))
    }

    /** The current copy ended before its cross fade started: carry straight on with the next one. */
    private fun advance() {
        Log.i(TAG, "song ended before the cross fade, starting it again")
        handler.removeCallbacks(crossfadeTask)
        current?.release()
        val incoming = next
        if (incoming != null) {
            next = null
            incoming.start()
            begin(incoming)
        } else {
            current = null
            open { it.start(); begin(it) }
        }
    }

    private fun crossfade() {
        val outgoing = current ?: return
        val incoming = next
        if (incoming == null) {
            handler.postDelayed(crossfadeTask, 200) // still preparing
            return
        }
        next = null
        Log.i(TAG, "cross fading into the start of the song")
        fading = true
        incoming.setVolume(0f, 0f)
        incoming.start()
        val startedAt = System.nanoTime()
        object : Runnable {
            override fun run() {
                val t = ((System.nanoTime() - startedAt) / 1_000_000f / fadeMs).coerceAtMost(1f)
                // equal-power curves keep the loudness steady through the overlap
                outgoing.setVolume(cos(t * PI.toFloat() / 2), cos(t * PI.toFloat() / 2))
                incoming.setVolume(sin(t * PI.toFloat() / 2), sin(t * PI.toFloat() / 2))
                if (t < 1f) {
                    handler.postDelayed(this, 100)
                } else {
                    fading = false
                    outgoing.release()
                    begin(incoming)
                }
            }
        }.run()
    }

    private fun open(onReady: (MediaPlayer) -> Unit) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(context, uri)
            p.setOnPreparedListener { if (stopped) it.release() else onReady(it) }
            p.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (!stopped) onFailed()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            p.release()
            if (!stopped) onFailed()
        }
    }
}
